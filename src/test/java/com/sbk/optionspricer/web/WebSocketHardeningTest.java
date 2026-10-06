package com.sbk.optionspricer.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketHardeningTest {
    private OptionsDashboardServer server;
    private Path webRoot;

    @BeforeEach
    void startServer() throws Exception {
        webRoot = Files.createTempDirectory("ws-hardening-test-web");
        MmapStateReader reader = new MmapStateReader(true) {
            @Override
            public RiskState readState() {
                return new RiskState(1.5, 2.5, 3.5, 4.5);
            }

            @Override
            public void close() {}
        };
        server = new OptionsDashboardServer(OptionsDashboardServerTest.TEST_SECRET,
                OptionsDashboardServerTest.OPERATOR_PASSWORD, OptionsDashboardServerTest.ALLOWED_ORIGIN,
                "127.0.0.1", 0, 0, reader, webRoot.toString(), null, null, null);
        server.start();
        OptionsDashboardServerTest.waitForWebSocketPort(server);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.stop();
        if (webRoot != null) Files.deleteIfExists(webRoot);
    }

    private String loginCookie() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/login"))
                .header("Origin", OptionsDashboardServerTest.ALLOWED_ORIGIN)
                .POST(HttpRequest.BodyPublishers.ofString(OptionsDashboardServerTest.OPERATOR_PASSWORD, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private void logout(String cookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/logout"))
                .header("Origin", OptionsDashboardServerTest.ALLOWED_ORIGIN)
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(200, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /** Collects binary frames and records when the server closes the connection. */
    private static final class Collector implements WebSocket.Listener {
        final AtomicInteger frames = new AtomicInteger();
        final AtomicReference<ByteBuffer> firstFrame = new AtomicReference<>();
        final CountDownLatch gotFrame = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data).flip();
            if (firstFrame.compareAndSet(null, copy)) gotFrame.countDown();
            frames.incrementAndGet();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }
    }

    private CompletableFuture<WebSocket> openClient(String cookie, Collector collector) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", OptionsDashboardServerTest.ALLOWED_ORIGIN)
                .header("Cookie", cookie)
                .buildAsync(URI.create("ws://127.0.0.1:" + server.getWebSocketPort() + "/ws"), collector);
    }

    @Test
    void authenticatedClientReceivesTheFourDoubleRiskFrame() throws Exception {
        Collector collector = new Collector();
        WebSocket socket = openClient(loginCookie(), collector).get(10, TimeUnit.SECONDS);
        try {
            assertTrue(collector.gotFrame.await(5, TimeUnit.SECONDS), "a risk frame must arrive");
            ByteBuffer frame = collector.firstFrame.get();
            assertEquals(32, frame.remaining(), "payload is 4 x 8-byte big-endian doubles");
            assertEquals(1.5, frame.getDouble(), 0.0);
            assertEquals(2.5, frame.getDouble(), 0.0);
            assertEquals(3.5, frame.getDouble(), 0.0);
            assertEquals(4.5, frame.getDouble(), 0.0);
        } finally {
            socket.abort();
        }
    }

    @Test
    void handshakeSplitAcrossTcpSegmentsStillSucceeds() throws Exception {
        String cookie = loginCookie();
        String request = "GET /ws HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + server.getWebSocketPort() + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Origin: " + OptionsDashboardServerTest.ALLOWED_ORIGIN + "\r\n" +
                "Cookie: " + cookie + "\r\n" +
                // gitleaks:allow
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
        try (Socket socket = new Socket("127.0.0.1", server.getWebSocketPort())) {
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            int split = 40; // mid-header
            out.write(bytes, 0, split);
            out.flush();
            Thread.sleep(300);
            out.write(bytes, split, bytes.length - split);
            out.flush();

            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertEquals("HTTP/1.1 101 Switching Protocols", status);
        }
    }

    @Test
    void loggingOutClosesTheLiveWebSocket() throws Exception {
        String cookie = loginCookie();
        Collector collector = new Collector();
        WebSocket socket = openClient(cookie, collector).get(10, TimeUnit.SECONDS);
        try {
            assertTrue(collector.gotFrame.await(5, TimeUnit.SECONDS));

            logout(cookie);

            assertTrue(collector.closed.await(5, TimeUnit.SECONDS),
                    "a session that has been logged out must not keep receiving live risk data");
        } finally {
            socket.abort();
        }
    }
}
