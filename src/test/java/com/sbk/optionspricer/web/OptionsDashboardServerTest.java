package com.sbk.optionspricer.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OptionsDashboardServerTest {
        static final String TEST_SECRET = java.util.UUID.randomUUID().toString()
            + java.util.UUID.randomUUID().toString();
        static final String OPERATOR_PASSWORD = "operator-" + java.util.UUID.randomUUID();
    static final String ALLOWED_ORIGIN = "http://dashboard.test";

    private OptionsDashboardServer server;
    private Path webRoot;

    @BeforeEach
    void startServer() throws Exception {
        webRoot = Files.createTempDirectory("dashboard-test-web");
        Files.writeString(webRoot.resolve("index.html"), "test dashboard");
        MmapStateReader reader = new MmapStateReader(true) {
            @Override
            public RiskState readState() {
                return new RiskState(1.0, 2.0, 3.0, 40000.0);
            }

            @Override
            public void close() {}
        };
        server = new OptionsDashboardServer(TEST_SECRET, OPERATOR_PASSWORD, ALLOWED_ORIGIN,
                "127.0.0.1", 0, 0, reader, webRoot.toString(), null, null, null);
        server.start();
        waitForWebSocketPort(server);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.stop();
        if (webRoot != null) {
            Files.deleteIfExists(webRoot.resolve("index.html"));
            Files.deleteIfExists(webRoot);
        }
    }

    static void waitForWebSocketPort(OptionsDashboardServer server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.getWebSocketPort() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(server.getWebSocketPort() > 0, "WebSocket listener did not start");
    }

    private HttpURLConnection request(String path) throws Exception {
        return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getPort() + path).openConnection();
    }

    private HttpResponse<String> login(String password) throws Exception {
        HttpRequest loginRequest = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + server.getPort() + "/login"))
                .header("Origin", ALLOWED_ORIGIN)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(password, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(loginRequest, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyApiRouteRejectsRequestsWithoutASession() throws Exception {
        for (String route : new String[]{"/api/spot", "/api/risk", "/api/surface3d"}) {
            assertEquals(401, request(route).getResponseCode(), route);
        }
    }

    @Test
    void loginIssuesStrictHttpOnlySessionCookieAndCookieAuthorizesApi() throws Exception {
        HttpResponse<String> login = login(OPERATOR_PASSWORD);
        assertEquals(200, login.statusCode());
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Max-Age=" + BrowserSessionManager.SESSION_MAX_AGE_SECONDS));

        HttpRequest riskRequest = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.getPort() + "/api/risk"))
            .header("Origin", ALLOWED_ORIGIN)
            .header("Cookie", cookie.split(";", 2)[0])
            .GET()
            .build();
        HttpResponse<String> risk = HttpClient.newHttpClient().send(riskRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, risk.statusCode());
        assertTrue(risk.body().contains("\"scenarioMargin\":40000.00"));

        HttpRequest wrongOriginRequest = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.getPort() + "/api/risk"))
            .header("Origin", "http://attacker.test")
            .header("Cookie", cookie.split(";", 2)[0])
            .GET()
            .build();
        assertEquals(401, HttpClient.newHttpClient().send(wrongOriginRequest,
            HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void riskEndpointSanitizesNonFiniteValues() throws Exception {
        MmapStateReader invalidReader = new MmapStateReader(true) {
            @Override
            public RiskState readState() {
                return new RiskState(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }

            @Override
            public void close() {}
        };

        OptionsDashboardServer invalidServer = new OptionsDashboardServer(TEST_SECRET, OPERATOR_PASSWORD,
                ALLOWED_ORIGIN, "127.0.0.1", 0, 0, invalidReader, webRoot.toString(), null, null, null);
        invalidServer.start();
        waitForWebSocketPort(invalidServer);
        try {
            HttpRequest loginRequest = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + invalidServer.getPort() + "/login"))
                    .header("Origin", ALLOWED_ORIGIN)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(OPERATOR_PASSWORD, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> login = HttpClient.newHttpClient().send(loginRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode(), "login should succeed against invalidServer");
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow();
            HttpRequest riskRequest = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + invalidServer.getPort() + "/api/risk"))
                    .header("Origin", ALLOWED_ORIGIN)
                    .header("Cookie", cookie.split(";", 2)[0])
                    .GET()
                    .build();
            HttpResponse<String> risk = HttpClient.newHttpClient().send(riskRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, risk.statusCode());
            assertTrue(!risk.body().contains("NaN"), risk.body());
            assertTrue(risk.body().contains("\"netDelta\":0.00"), risk.body());
        } finally {
            invalidServer.stop();
        }
    }

    @Test
    void loginRateLimitsAfterFiveAttempts() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(401, login("wrong-password").statusCode());
        }
        assertEquals(429, login("wrong-password").statusCode());
    }

    @Test
    void missingAndShortApiSecretsFailBeforeStartup() throws Exception {
        assertStartupFails(null, "API_SECRET");
        assertStartupFails("too-short", "API_SECRET");
    }

    @Test
    void constructorRejectsMissingOrShortApiSecret() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OptionsDashboardServer.validateApiSecret(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OptionsDashboardServer.validateApiSecret("short"));
    }

    private static void assertStartupFails(String secret, String expectedText) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("java", "--add-modules", "jdk.incubator.vector",
                "-cp", System.getProperty("java.class.path"),
                "com.sbk.optionspricer.Main");
        if (secret == null) processBuilder.environment().remove("API_SECRET");
        else processBuilder.environment().put("API_SECRET", secret);
        processBuilder.environment().put("OPERATOR_PASSWORD", OPERATOR_PASSWORD);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Startup validation process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertNotEquals(0, process.exitValue());
        assertTrue(output.contains(expectedText), output);
    }
}
