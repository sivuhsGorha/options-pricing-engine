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
                new Thread(() -> handleHandshake(clientSocket), "ws-client-" + clientSocket.getPort()).start();
            }
        } catch (Exception e) {
            System.err.println("WebSocket Server stopped: " + e.getMessage());
        }
    }

    private void handleHandshake(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] buffer = new byte[2048];
            int read = in.read(buffer);
            if (read <= 0) return;

            String request = new String(buffer, 0, read);
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
            }
        } catch (Exception e) {
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

    private String extractHeader(String request, String headerName) {
        int start = request.indexOf(headerName) + headerName.length();
        int end = request.indexOf("\r\n", start);
        return request.substring(start, end).trim();
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
