package com.sbk.optionspricer.web;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zero-Allocation Binary WebSocket Server for ultra-low-latency market & risk telemetry.
 * Conforms to RFC 6455. Streams binary state updates (4 x 64-bit IEEE 754 doubles)
 * directly from memory-mapped files without JSON serialization garbage.
 */
public class WebSocketDashboardServer implements Runnable {

    private static final String WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final int port;
    private final MmapStateReader mmapReader;
    private final CopyOnWriteArrayList<Socket> activeClients = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private static final int MAX_CLIENTS = 100;
    private final ExecutorService clientExecutor = Executors.newFixedThreadPool(MAX_CLIENTS);
    private static final String API_SECRET = System.getenv("API_SECRET") != null ? System.getenv("API_SECRET") : "default-dev-secret";
    private static final String ALLOWED_ORIGIN = System.getenv("ALLOWED_ORIGIN") != null ? System.getenv("ALLOWED_ORIGIN") : "http://localhost:3000";

    public WebSocketDashboardServer(int port, MmapStateReader mmapReader) {
        this.port = port;
        this.mmapReader = mmapReader;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("WebSocket Live Feed Server listening on ws://localhost:" + port);

            // Background broadcaster thread
            Thread broadcastThread = new Thread(this::broadcastLoop, "ws-broadcaster");
            broadcastThread.setDaemon(true);
            broadcastThread.start();

            while (running) {
                Socket clientSocket = serverSocket.accept();
                try {
                    clientSocket.setSoTimeout(5000); // Handshake timeout
                    clientExecutor.submit(() -> handleClient(clientSocket));
                } catch (Exception e) {
                    try { clientSocket.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("WebSocket Server stopped: " + e.getMessage());
        } finally {
            clientExecutor.shutdownNow();
        }
    }

    private void handleClient(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] buffer = new byte[2048];
            int read = in.read(buffer);
            if (read <= 0) return;

            String request = new String(buffer, 0, read);
            
            if (activeClients.size() >= MAX_CLIENTS) {
                out.write("HTTP/1.1 429 Too Many Requests\r\n\r\n".getBytes());
                socket.close();
                return;
            }

            String origin = extractHeaderSafe(request, "Origin:");
            if (origin == null || !origin.equals(ALLOWED_ORIGIN)) {
                out.write("HTTP/1.1 403 Forbidden\r\n\r\n".getBytes());
                socket.close();
                return;
            }

            String signature = extractHeaderSafe(request, "X-Signature:");
            String timestamp = extractHeaderSafe(request, "X-Timestamp:");
            // For WebSocket handshake, path is usually "/" or "/ws"
            String path = extractPath(request);
            if (!HmacAuth.verify(API_SECRET, signature, "GET", path, timestamp)) {
                out.write("HTTP/1.1 401 Unauthorized\r\n\r\n".getBytes());
                socket.close();
                return;
            }

            if (request.contains("Sec-WebSocket-Key")) {
                String key = extractHeader(request, "Sec-WebSocket-Key:");
                String acceptKey = generateAcceptKey(key);

                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";

                out.write(response.getBytes());
                out.flush();

                activeClients.add(socket);
                System.out.println("New WebSocket Client connected: " + socket.getRemoteSocketAddress());
                
                // Read loop to detect client disconnect and enforce idle timeout
                socket.setSoTimeout(30000); // 30s idle timeout
                byte[] discardBuffer = new byte[1024];
                while (running && !socket.isClosed()) {
                    int r = in.read(discardBuffer);
                    if (r == -1) break; // Client closed connection
                }
            }
        } catch (Exception e) {
            // Idle timeout or error
        } finally {
            activeClients.remove(socket);
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void broadcastLoop() {
        ByteBuffer frameBuffer = ByteBuffer.allocate(34); // 2 bytes header + 32 bytes data (4 doubles)
        frameBuffer.order(ByteOrder.BIG_ENDIAN);

        while (running) {
            try {
                Thread.sleep(50); // 20 Hz update rate

                if (activeClients.isEmpty()) continue;

                // Build Binary WebSocket Frame (Opcode 0x2 - Binary)
                frameBuffer.clear();
                frameBuffer.put((byte) 0x82); // FIN + Binary frame
                frameBuffer.put((byte) 32);   // Payload length = 32 bytes (4 * 8-byte doubles)

                // Write 4 off-heap doubles directly
                frameBuffer.putDouble(mmapReader.getNetDelta());
                frameBuffer.putDouble(mmapReader.getNetGamma());
                frameBuffer.putDouble(mmapReader.getNetVega());
                frameBuffer.putDouble(mmapReader.getSpanMargin());

                byte[] rawFrame = frameBuffer.array();

                for (Socket socket : activeClients) {
                    try {
                        OutputStream out = socket.getOutputStream();
                        out.write(rawFrame);
                        out.flush();
                    } catch (Exception e) {
                        activeClients.remove(socket);
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Ignore transient write errors
            }
        }
    }

    private String extractHeaderSafe(String request, String headerName) {
        int start = request.indexOf(headerName);
        if (start == -1) return null;
        start += headerName.length();
        int end = request.indexOf("\r\n", start);
        if (end == -1) return null;
        return request.substring(start, end).trim();
    }

    private String extractPath(String request) {
        if (request.startsWith("GET ")) {
            int end = request.indexOf(" HTTP/");
            if (end != -1) return request.substring(4, end).trim();
        }
        return "/";
    }

    private String extractHeader(String request, String headerName) {
        return extractHeaderSafe(request, headerName);
    }

    private String generateAcceptKey(String key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest((key + WS_MAGIC_GUID).getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(hash);
    }

    public void stop() {
        this.running = false;
    }
}
