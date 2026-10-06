package com.sbk.optionspricer.web;

import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Binary WebSocket feed of risk telemetry: each frame is four big-endian 64-bit doubles
 * (net delta, gamma, vega, scenario margin) read from the memory-mapped state at 20 Hz.
 *
 * <p>Built on Jetty's WebSocket implementation. Handshake authentication, origin checks and
 * connection limits run before the upgrade completes; each client has at most one frame in flight,
 * so a slow client drops frames instead of delaying the others; and browser sessions are
 * re-validated on every tick, so logging out or session expiry ends the stream.
 */
public class WebSocketDashboardServer implements Runnable {

    public static final int DEFAULT_MAX_CONNECTIONS_PER_IP = 5;
    private static final int MAX_CLIENTS = 100;
    private static final long BROADCAST_INTERVAL_MS = 50; // 20 Hz
    private static final int CLOSE_POLICY_VIOLATION = 1008;

    private final String bindAddress;
    private final int port;
    private final MmapStateReader mmapReader;
    private final String apiSecret;
    private final BrowserSessionManager sessions;
    private final Set<String> trustedProxies;
    private final int maxConnectionsPerIp = Integer.parseInt(
            System.getProperty("WS_MAX_CONNECTIONS_PER_IP", String.valueOf(DEFAULT_MAX_CONNECTIONS_PER_IP)));

    private final CopyOnWriteArrayList<FeedClient> clients = new CopyOnWriteArrayList<>();
    private final Semaphore clientSlots = new Semaphore(MAX_CLIENTS);
    private final ConcurrentHashMap<String, AtomicInteger> connectionsByIp = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private volatile int boundPort;
    private volatile Server jetty;
    private long lastConnectLogTime = 0;

    public WebSocketDashboardServer(String bindAddress, int port, MmapStateReader mmapReader, String apiSecret,
                                    BrowserSessionManager sessions) {
        this(bindAddress, port, mmapReader, apiSecret, sessions, ClientAddressResolver.parseTrustedProxies(
                com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("TRUSTED_PROXIES", "")));
    }

    public WebSocketDashboardServer(String bindAddress, int port, MmapStateReader mmapReader, String apiSecret,
                                    BrowserSessionManager sessions, Set<String> trustedProxies) {
        OptionsDashboardServer.validateApiSecret(apiSecret);
        this.bindAddress = (bindAddress == null || bindAddress.isBlank()) ? "127.0.0.1" : bindAddress;
        this.port = port;
        this.mmapReader = mmapReader;
        this.apiSecret = apiSecret;
        this.sessions = sessions;
        this.trustedProxies = Set.copyOf(trustedProxies);
    }

    public WebSocketDashboardServer(int port, MmapStateReader mmapReader, String apiSecret,
                                    BrowserSessionManager sessions) {
        this("127.0.0.1", port, mmapReader, apiSecret, sessions);
    }

    public int getPort() {
        return boundPort;
    }

    /** Starts the server, then runs the broadcast loop on the calling thread until {@link #stop()}. */
    @Override
    public void run() {
        Server server = new Server(new QueuedThreadPool(32, 4));
        try {
            ServerConnector connector = new ServerConnector(server);
            connector.setHost(bindAddress);
            connector.setPort(port);
            connector.setIdleTimeout(5_000); // bounds a stalled or trickling handshake
            server.addConnector(connector);

            ContextHandler context = new ContextHandler("/");
            context.setHandler(WebSocketUpgradeHandler.from(server, context, container -> {
                container.setIdleTimeout(Duration.ofSeconds(60));
                container.setMaxBinaryMessageSize(1024);
                container.setMaxTextMessageSize(1024);
                container.addMapping("/*", this::createEndpoint);
            }));
            server.setHandler(context);
            server.start();
            jetty = server;
            boundPort = connector.getLocalPort();
            System.out.println("WebSocket Live Feed Server listening on ws://" + bindAddress + ":" + boundPort);

            broadcastLoop();
        } catch (Exception e) {
            System.err.println("WebSocket Server stopped: " + e.getMessage());
        } finally {
            jetty = null;
            try {
                server.stop();
            } catch (Exception ignored) {
                // already stopped
            }
        }
    }

    /** Runs before the upgrade: returns the endpoint, or writes an HTTP error and returns null. */
    private Object createEndpoint(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback) {
        String origin = request.getHeaders().get("Origin");
        if (origin != null && !sessions.isAllowedOrigin(origin)) {
            return reject(request, response, callback, 403);
        }

        String cookie = null;
        boolean authorized;
        if (origin != null) {
            cookie = request.getHeaders().get("Cookie");
            authorized = sessions.isValidSessionCookie(cookie);
        } else {
            authorized = HmacAuth.verify(apiSecret, request.getHeaders().get("X-Signature"),
                    "GET", request.getHttpURI().getPath(), request.getHttpURI().getQuery(),
                    request.getHeaders().get("X-Timestamp"), request.getHeaders().get("X-Nonce"));
        }
        if (!authorized) {
            return reject(request, response, callback, 401);
        }

        String clientIp = resolveClientIp(request);
        boolean loopback = "127.0.0.1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp) || "::1".equals(clientIp);
        AtomicInteger perIp = connectionsByIp.computeIfAbsent(clientIp, k -> new AtomicInteger());
        if (perIp.incrementAndGet() > (loopback ? MAX_CLIENTS : maxConnectionsPerIp)) {
            releaseIp(clientIp);
            return reject(request, response, callback, 429);
        }
        if (!clientSlots.tryAcquire()) {
            releaseIp(clientIp);
            return reject(request, response, callback, 429);
        }
        return new FeedClient(clientIp, origin != null ? cookie : null);
    }

    private static Object reject(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback, int status) {
        Response.writeError(request, response, callback, status);
        return null;
    }

    private String resolveClientIp(ServerUpgradeRequest request) {
        InetSocketAddress remote = request.getConnectionMetaData().getRemoteSocketAddress() instanceof InetSocketAddress a ? a : null;
        return ClientAddressResolver.resolve(remote, request.getHeaders().get("X-Forwarded-For"), trustedProxies);
    }

    private void releaseIp(String clientIp) {
        connectionsByIp.computeIfPresent(clientIp, (ip, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private void broadcastLoop() {
        while (running) {
            try {
                Thread.sleep(BROADCAST_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (clients.isEmpty()) continue;

            byte[] frame;
            try {
                MmapStateReader.RiskState state = mmapReader.readState();
                frame = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                        .putDouble(state.netDelta).putDouble(state.netGamma)
                        .putDouble(state.netVega).putDouble(state.scenarioMargin).array();
            } catch (IllegalStateException e) {
                continue; // state unavailable: skip this tick
            } catch (RuntimeException e) {
                continue;
            }

            for (FeedClient client : clients) {
                client.push(frame);
            }
        }
    }

    public void stop() {
        running = false;
        Server server = jetty;
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
                // best effort; run() also stops the server on exit
            }
        }
    }

    /** One connected client. At most one frame is in flight; frames for a slow client are dropped. */
    public final class FeedClient implements Session.Listener.AutoDemanding {
        private final String clientIp;
        private final String sessionCookie; // null for HMAC (non-browser) clients
        private final AtomicBoolean sending = new AtomicBoolean(false);
        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile Session session;

        FeedClient(String clientIp, String sessionCookie) {
            this.clientIp = clientIp;
            this.sessionCookie = sessionCookie;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            this.session = session;
            clients.add(this);
            long now = System.currentTimeMillis();
            if (now - lastConnectLogTime > 1000) {
                System.out.println("New WebSocket Client connected: " + session.getRemoteSocketAddress() + " (Total: " + clients.size() + ")");
                lastConnectLogTime = now;
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            release();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            release();
        }

        void push(byte[] frame) {
            Session s = session;
            if (s == null) return;
            if (!s.isOpen()) {
                release();
                return;
            }
            if (sessionCookie != null && !sessions.isSessionActive(sessionCookie)) {
                s.close(CLOSE_POLICY_VIOLATION, "session ended", org.eclipse.jetty.websocket.api.Callback.NOOP);
                release();
                return;
            }
            if (!sending.compareAndSet(false, true)) {
                return; // previous frame still in flight: this client is slow, drop rather than queue
            }
            s.sendBinary(ByteBuffer.wrap(frame), org.eclipse.jetty.websocket.api.Callback.from(
                    () -> sending.set(false),
                    failure -> {
                        sending.set(false);
                        release();
                    }));
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                clients.remove(this);
                clientSlots.release();
                releaseIp(clientIp);
            }
        }
    }
}
