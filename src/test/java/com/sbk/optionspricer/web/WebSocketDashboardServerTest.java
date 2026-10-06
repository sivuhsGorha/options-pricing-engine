package com.sbk.optionspricer.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebSocketDashboardServerTest {
    private OptionsDashboardServer server;
    private Path webRoot;

    @BeforeEach
    void startServer() throws Exception {
        webRoot = Files.createTempDirectory("ws-dashboard-test-web");
        MmapStateReader reader = new MmapStateReader(true) {
            @Override
            public RiskState readState() {
                throw new IllegalStateException("UNAVAILABLE");
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.getPort() + "/login"))
            .header("Origin", OptionsDashboardServerTest.ALLOWED_ORIGIN)
            .POST(HttpRequest.BodyPublishers.ofString(OptionsDashboardServerTest.OPERATOR_PASSWORD,
                StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private Socket connect(String origin, String cookie) throws Exception {
        Socket socket = new Socket("127.0.0.1", server.getWebSocketPort());
        socket.setSoTimeout(5000);
        OutputStream output = socket.getOutputStream();
        String request = "GET /ws HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + server.getWebSocketPort() + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Origin: " + origin + "\r\n" +
                (cookie == null ? "" : "Cookie: " + cookie + "\r\n") +
                // gitleaks:allow
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        return socket;
    }

    private String readStatus(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
    }

    @Test
    void wrongOriginIsRejectedEvenWithAValidSession() throws Exception {
        try (Socket socket = connect("http://attacker.test", loginCookie())) {
            assertEquals("HTTP/1.1 403 Forbidden", readStatus(socket));
        }
    }

    @Test
    void oneHundredFirstConcurrentConnectionGets429() throws Exception {
        String cookie = loginCookie();
        List<Socket> sockets = new ArrayList<>();
        try {
            for (int index = 0; index < 100; index++) {
                Socket socket = connect(OptionsDashboardServerTest.ALLOWED_ORIGIN, cookie);
                sockets.add(socket);
                assertEquals("HTTP/1.1 101 Switching Protocols", readStatus(socket), "connection " + (index + 1));
            }
            try (Socket excess = connect(OptionsDashboardServerTest.ALLOWED_ORIGIN, cookie)) {
                assertEquals("HTTP/1.1 429 Too Many Requests", readStatus(excess));
            }
        } finally {
            for (Socket socket : sockets) socket.close();
        }
    }
}