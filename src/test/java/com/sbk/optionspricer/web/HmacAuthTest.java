package com.sbk.optionspricer.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HmacAuthTest {

    private static HttpServer server;
        private static final String SECRET = java.util.UUID.randomUUID().toString()
            + java.util.UUID.randomUUID().toString();
    private static final int PORT = 8085;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/spot", exchange -> {
            String signature = exchange.getRequestHeaders().getFirst("X-Signature");
            String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
            String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
            
            if (!HmacAuth.verify(SECRET, signature, exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(), timestamp, nonce)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            byte[] response = "OK".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    private String sign(String secret, String method, String path, String query, String timestamp, String nonce) throws Exception {
        if (nonce == null) nonce = "";
        if (timestamp == null) timestamp = "";
        if (query == null) query = "";
        String payload = method + "\n" + path + "\n" + query + "\n" + timestamp + "\n" + nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpURLConnection send(String path, String timestamp, String nonce, String signature) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + PORT + path).openConnection();
        conn.setRequestMethod("GET");
        if (timestamp != null) conn.setRequestProperty("X-Timestamp", timestamp);
        if (nonce != null) conn.setRequestProperty("X-Nonce", nonce);
        if (signature != null) conn.setRequestProperty("X-Signature", signature);
        conn.connect();
        return conn;
    }

    @Test
    void testValid() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(SECRET, "GET", "/api/spot", null, ts, "nonce1");
        assertEquals(200, send("/api/spot", ts, "nonce1", sig).getResponseCode());
    }

    @Test
    void testWrongKey() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign("wrong-key", "GET", "/api/spot", null, ts, "nonce2");
        assertEquals(401, send("/api/spot", ts, "nonce2", sig).getResponseCode());
    }

    @Test
    void testTamperedBody() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(SECRET, "GET", "/api/spot", null, ts, "nonce3");
        // Tampered path
        assertEquals(401, send("/api/spott", ts, "nonce3", sig).getResponseCode());
    }

    @Test
    void testReplayedNonce() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(SECRET, "GET", "/api/spot", null, ts, "nonce4");
        assertEquals(200, send("/api/spot", ts, "nonce4", sig).getResponseCode());
        assertEquals(401, send("/api/spot", ts, "nonce4", sig).getResponseCode());
    }

    @Test
    void testExpiredTimestamp() throws Exception {
        String ts = String.valueOf((System.currentTimeMillis() / 1000) - 400);
        String sig = sign(SECRET, "GET", "/api/spot", null, ts, "nonce5");
        assertEquals(401, send("/api/spot", ts, "nonce5", sig).getResponseCode());
    }

    @Test
    void testFutureTimestamp() throws Exception {
        String ts = String.valueOf((System.currentTimeMillis() / 1000) + 400);
        String sig = sign(SECRET, "GET", "/api/spot", null, ts, "nonce6");
        assertEquals(401, send("/api/spot", ts, "nonce6", sig).getResponseCode());
    }

    @Test
    void testFutureTimestampReplayAfterOldTTL() throws Exception {
        String ts = String.valueOf((System.currentTimeMillis() / 1000) + 300);
        String sig = sign(SECRET, "GET", "/api/spot", null, ts, "nonce-future-replay");
        assertEquals(401, send("/api/spot", ts, "nonce-future-replay", sig).getResponseCode());
    }

    @Test
    void testMissingHeader() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(SECRET, "GET", "/api/spot", null, ts, "nonce7");
        assertEquals(401, send("/api/spot", null, "nonce7", sig).getResponseCode());
        assertEquals(401, send("/api/spot", ts, null, sig).getResponseCode());
        assertEquals(401, send("/api/spot", ts, "nonce7", null).getResponseCode());
    }
}
