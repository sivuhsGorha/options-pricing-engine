package com.sbk.optionspricer.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerHardeningTest {
    private OptionsDashboardServer server;
    private Path webRoot;

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.stop();
    }

    private void start(OptionsDashboardServer.SecurityOptions options) throws Exception {
        webRoot = Files.createTempDirectory("http-hardening-web");
        Files.writeString(webRoot.resolve("index.html"), "<html></html>");
        Files.createDirectories(webRoot.resolve("assets"));
        Files.writeString(webRoot.resolve("assets").resolve("index-abc123.js"), "console.log(1)");
        MmapStateReader reader = new MmapStateReader(true) {
            @Override
            public RiskState readState() {
                return new RiskState(0, 0, 0, 0);
            }

            @Override
            public void close() {}
        };
        server = new OptionsDashboardServer(OptionsDashboardServerTest.TEST_SECRET, OptionsDashboardServerTest.OPERATOR_PASSWORD,
                OptionsDashboardServerTest.ALLOWED_ORIGIN, "127.0.0.1", 0, 0, reader, webRoot.toString(), null, null, null, options);
        server.start();
        OptionsDashboardServerTest.waitForWebSocketPort(server);
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path)).GET();
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/login"))
                .header("Origin", OptionsDashboardServerTest.ALLOWED_ORIGIN)
                .POST(HttpRequest.BodyPublishers.ofString(OptionsDashboardServerTest.OPERATOR_PASSWORD, StandardCharsets.UTF_8));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------- 3.7 cookies, CSP, caching ----------

    @Test
    void aClientCannotMakeTheServerThinkTheConnectionIsSecureByForgingAHeader() throws Exception {
        start(new OptionsDashboardServer.SecurityOptions(Set.of(), false));

        HttpResponse<String> response = login("X-Forwarded-Proto", "https");

        assertEquals(200, response.statusCode());
        assertFalse(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"),
                "forwarded-proto from a peer that is not a trusted proxy must be ignored");
        assertTrue(response.headers().firstValue("Strict-Transport-Security").isEmpty());
    }

    @Test
    void forwardedProtoIsHonouredOnlyFromATrustedProxy() throws Exception {
        start(new OptionsDashboardServer.SecurityOptions(Set.of("127.0.0.1"), false));

        HttpResponse<String> response = login("X-Forwarded-Proto", "https");

        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"));
        assertTrue(response.headers().firstValue("Strict-Transport-Security").isPresent());
    }

    @Test
    void cookieSecureFlagForcesSecureCookiesAndHsts() throws Exception {
        start(new OptionsDashboardServer.SecurityOptions(Set.of(), true));

        HttpResponse<String> response = login();

        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"));
        assertTrue(response.headers().firstValue("Strict-Transport-Security").isPresent());
    }

    @Test
    void contentSecurityPolicyAllowsNoInlineScriptsAndNoPlainWebSockets() throws Exception {
        start(new OptionsDashboardServer.SecurityOptions(Set.of(), false));

        String csp = get("/index.html").headers().firstValue("Content-Security-Policy").orElseThrow();

        String scriptSrc = java.util.Arrays.stream(csp.split(";")).map(String::trim)
                .filter(d -> d.startsWith("script-src")).findFirst().orElseThrow();
        String connectSrc = java.util.Arrays.stream(csp.split(";")).map(String::trim)
                .filter(d -> d.startsWith("connect-src")).findFirst().orElseThrow();
        assertFalse(scriptSrc.contains("'unsafe-inline'"), scriptSrc);
        assertFalse(scriptSrc.contains("'unsafe-eval'"), scriptSrc);
        assertEquals("connect-src 'self'", connectSrc, "the UI makes same-origin requests only");
        assertTrue(csp.contains("frame-ancestors 'none'"));
    }

    @Test
    void hashedAssetsAreCachedForeverAndTheShellIsAlwaysRevalidated() throws Exception {
        start(new OptionsDashboardServer.SecurityOptions(Set.of(), false));

        HttpResponse<String> asset = get("/assets/index-abc123.js");
        HttpResponse<String> shell = get("/index.html");

        assertEquals(200, asset.statusCode());
        assertEquals("public, max-age=31536000, immutable", asset.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", shell.headers().firstValue("Cache-Control").orElseThrow());
    }

    // ---------- 3.4 resources ----------

    @Test
    void handlerExecutorIsBoundedSoAFloodCannotExhaustThreadsOrMemory() {
        ThreadPoolExecutor executor = OptionsDashboardServer.createHttpExecutor();
        try {
            assertEquals(32, executor.getMaximumPoolSize());
            assertEquals(256, executor.getQueue().remainingCapacity() + executor.getQueue().size());
            assertTrue(executor.getThreadFactory().newThread(() -> {}).isDaemon(), "handler threads must not block JVM exit");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aRequestThatNeverCompletesIsClosedByTheServer() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Path dir = Files.createTempDirectory("http-timeout-app");
        ProcessBuilder builder = new ProcessBuilder("java", "--add-modules", "jdk.incubator.vector",
                "-cp", System.getProperty("java.class.path"), "com.sbk.optionspricer.Main");
        builder.directory(dir.toFile());
        builder.environment().put("API_SECRET", "t".repeat(40));
        builder.environment().put("OPERATOR_PASSWORD", "operator-password-123");
        builder.environment().put("PORT", String.valueOf(port));
        builder.environment().put("MMAP_STATE_FILE", "state.dat");
        builder.environment().put("HTTP_REQUEST_TIMEOUT_SECONDS", "2");
        builder.redirectErrorStream(true);
        builder.redirectOutput(dir.resolve("app.log").toFile());
        Process app = builder.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
            boolean up = false;
            while (System.nanoTime() < deadline && app.isAlive() && !up) {
                try (Socket s = new Socket("127.0.0.1", port)) {
                    up = true;
                } catch (java.io.IOException notYet) {
                    Thread.sleep(200);
                }
            }
            assertTrue(up, "the app under test did not start; log: " + Files.readString(dir.resolve("app.log")));

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(12_000);
                // A request line and one header, but never the blank line that ends the headers.
                socket.getOutputStream().write("GET /api/health HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                long started = System.nanoTime();
                InputStream in = socket.getInputStream();
                int result;
                try {
                    result = in.read();
                } catch (SocketTimeoutException stillOpen) {
                    fail("the server kept an incomplete request open for 12s (HTTP_REQUEST_TIMEOUT_SECONDS=2)");
                    return;
                } catch (java.io.IOException reset) {
                    result = -1; // connection reset also counts as closed by the server
                }
                long elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L;
                assertEquals(-1, result, "the server should close the connection without sending a response");
                assertTrue(elapsedSeconds <= 11, "closed after " + elapsedSeconds + "s");
            }
        } finally {
            app.destroyForcibly();
        }
    }
}
