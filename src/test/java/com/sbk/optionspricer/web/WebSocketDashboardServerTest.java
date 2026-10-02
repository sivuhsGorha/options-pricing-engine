package com.sbk.optionspricer.web;

import com.sbk.optionspricer.core.MmapStatePublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebSocketDashboardServerTest {

    private static WebSocketDashboardServer server;
    private static final int PORT = 8087;

    @BeforeAll
    static void startServer() {
        MmapStateReader mockReader = new MmapStateReader() {
            @Override
            public RiskState readState() {
                throw new IllegalStateException("UNAVAILABLE");
            }
        };
        server = new WebSocketDashboardServer(PORT, mockReader);
        Thread t = new Thread(server);
        t.setDaemon(true);
        t.start();
        try { Thread.sleep(200); } catch (Exception e) {}
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    void testUnauthenticatedUpgradeRejected() throws Exception {
        try (Socket socket = new Socket("localhost", PORT)) {
            OutputStream out = socket.getOutputStream();
            String req = "GET /ws HTTP/1.1\r\n" +
                         "Host: localhost:" + PORT + "\r\n" +
                         "Upgrade: websocket\r\n" +
                         "Connection: Upgrade\r\n" +
                         "Origin: http://localhost:3000\r\n" +
                         "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                         "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes());
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];
            int read = in.read(buf);
            String response = new String(buf, 0, read);
            assertTrue(response.contains("401 Unauthorized"), "Expected 401 Unauthorized, got: " + response);
        }
    }

    @Test
    void testMaxClientsEnforced() throws Exception {
        // We will attempt to connect 101 clients. The 101st should get 429 Too Many Requests.
        // Wait, creating 101 sockets might take a bit.
        // Let's just create 105 sockets, read responses. At least one should be 429.
        List<Socket> sockets = new ArrayList<>();
        boolean got429 = false;
        try {
            for (int i = 0; i < 105; i++) {
                Socket socket = new Socket("localhost", PORT);
                sockets.add(socket);
                OutputStream out = socket.getOutputStream();
                
                // Valid auth for this test
                String ts = String.valueOf(System.currentTimeMillis() / 1000);
                String nonce = "n" + i;
                String method = "GET";
                String path = "/ws";
                String secret = "default-dev-secret";
                
                String payload = method + path + ts + nonce;
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
                String sig = java.util.Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

                String req = "GET /ws HTTP/1.1\r\n" +
                             "Host: localhost:" + PORT + "\r\n" +
                             "Upgrade: websocket\r\n" +
                             "Connection: Upgrade\r\n" +
                             "Origin: http://localhost:3000\r\n" +
                             "X-Timestamp: " + ts + "\r\n" +
                             "X-Nonce: " + nonce + "\r\n" +
                             "X-Signature: " + sig + "\r\n" +
                             "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                             "Sec-WebSocket-Version: 13\r\n\r\n";
                out.write(req.getBytes());
                out.flush();

                InputStream in = socket.getInputStream();
                byte[] buf = new byte[1024];
                int read = in.read(buf);
                if (read > 0) {
                    String response = new String(buf, 0, read);
                    if (response.contains("429 Too Many Requests")) {
                        got429 = true;
                        break;
                    }
                }
            }
            assertTrue(got429, "Expected at least one connection to be rejected with 429 Too Many Requests");
        } finally {
            for (Socket s : sockets) {
                try { s.close(); } catch (Exception e) {}
            }
        }
    }
}
