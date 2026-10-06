package com.sbk.optionspricer.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class SecurityHeadersAndSessionTest {
    private static final String TEST_SECRET = "0123456789012345678901234567890123456789";
    private static final String OPERATOR_PASSWORD = "StrongPassword123!";
    private static final String ALLOWED_ORIGIN = "http://dashboard.test";

    private OptionsDashboardServer server;
    private Path webRoot;

    @BeforeEach
    void setUp() throws Exception {
        webRoot = Files.createTempDirectory("security-headers-test");
        Files.writeString(webRoot.resolve("index.html"), "<html><body>Dashboard</body></html>");
        MmapStateReader reader = new MmapStateReader() {
            @Override
            public RiskState readState() {
                return new RiskState(5.0, 10.0, 15.0, 25000.0);
            }
            @Override
            public void close() {}
        };
        server = new OptionsDashboardServer(TEST_SECRET, OPERATOR_PASSWORD, ALLOWED_ORIGIN,
                "127.0.0.1", 0, 0, reader, webRoot.toString(), null, null, null);
        server.start();
        OptionsDashboardServerTest.waitForWebSocketPort(server);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (webRoot != null) {
            Files.deleteIfExists(webRoot.resolve("index.html"));
            Files.deleteIfExists(webRoot);
        }
    }

    @Test
    void securityHeadersArePresentOnApiAndStaticResponses() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Check static root
        HttpRequest staticReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/"))
                .GET().build();
        HttpResponse<String> staticRes = client.send(staticReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, staticRes.statusCode());
        assertTrue(staticRes.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header must be present");
        assertTrue(staticRes.headers().firstValue("Content-Security-Policy").get().contains("default-src 'self'"));
        assertEquals("nosniff", staticRes.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", staticRes.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("strict-origin-when-cross-origin", staticRes.headers().firstValue("Referrer-Policy").orElse(null));

        // Check login response headers
        HttpRequest loginReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/login"))
                .header("Origin", ALLOWED_ORIGIN)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(OPERATOR_PASSWORD, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> loginRes = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginRes.statusCode());
        assertTrue(loginRes.headers().firstValue("Cache-Control").isPresent());
        assertTrue(loginRes.headers().firstValue("Cache-Control").get().contains("no-store"));
    }

    @Test
    void logoutRevokesSessionImmediately() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Login first
        HttpRequest loginReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/login"))
                .header("Origin", ALLOWED_ORIGIN)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(OPERATOR_PASSWORD, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> loginRes = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginRes.statusCode());
        String cookie = loginRes.headers().firstValue("Set-Cookie").orElseThrow();

        // Verify API is accessible with cookie
        HttpRequest riskReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/risk"))
                .header("Origin", ALLOWED_ORIGIN)
                .header("Cookie", cookie.split(";", 2)[0])
                .GET().build();
        HttpResponse<String> riskRes = client.send(riskReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, riskRes.statusCode());

        // Call /logout
        HttpRequest logoutReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/logout"))
                .header("Origin", ALLOWED_ORIGIN)
                .header("Cookie", cookie.split(";", 2)[0])
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> logoutRes = client.send(logoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logoutRes.statusCode());
        String expiredCookie = logoutRes.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(expiredCookie.contains("Max-Age=0"), "Cookie must be expired on logout");

        // Subsequent API call with previous cookie must now be rejected (401)
        HttpResponse<String> postLogoutRes = client.send(riskReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, postLogoutRes.statusCode(), "Session must be invalid after logout");
    }

    @Test
    void wildcardAllowedOriginIsProhibited() {
        assertThrows(IllegalArgumentException.class, () ->
                new BrowserSessionManager(OPERATOR_PASSWORD, "*"));
        assertThrows(IllegalArgumentException.class, () ->
                new BrowserSessionManager(OPERATOR_PASSWORD, "http://localhost, *"));
    }
}
