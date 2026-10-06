package com.sbk.optionspricer.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StaticFileHandlerTraversalTest {

    private static HttpServer server;
    private static int port;
    private static Path tempDir;

    @BeforeAll
    static void startServer() throws Exception {
        // Create a temp web dir to ensure server serves files
        tempDir = Files.createTempDirectory("aura-test-web");
        Files.writeString(tempDir.resolve("test-index.html"), "hello");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new OptionsDashboardServer.StaticFileHandler(tempDir.toString()));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop(0);
        Files.deleteIfExists(tempDir.resolve("test-index.html"));
        Files.deleteIfExists(tempDir);
    }

    private void assertTraversalBlocked(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod("GET");
        conn.connect();
        
        int code = conn.getResponseCode();
        assertTrue(code >= 400 && code < 500, "Response must be 4xx, got " + code);
        
        // Assert no file bytes
        try (InputStream in = conn.getInputStream()) {
            // Should throw IOException for 4xx, but if it doesn't:
            byte[] buf = new byte[1024];
            int read = in.read(buf);
            assertTrue(read <= 0, "Expected no file bytes");
        } catch (java.io.IOException e) {
            // Expected for 4xx/5xx in HttpURLConnection
        }
    }

    @Test
    void testValidPath() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/test-index.html").openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
    }

    @Test
    void testTraversalDotDotSlash() throws Exception {
        assertTraversalBlocked("/../pom.xml");
    }

    @Test
    void testTraversalUrlEncodedDotDotSlash() throws Exception {
        assertTraversalBlocked("/%2e%2e/pom.xml");
    }

    @Test
    void testTraversalUrlEncodedSlash() throws Exception {
        // %2f is / 
        assertTraversalBlocked("/..%2fpom.xml");
    }

    @Test
    void testTraversalBackslash() throws Exception {
        assertTraversalBlocked("/..\\pom.xml");
    }

    @Test
    void testTraversalDoubleEncoding() throws Exception {
        assertTraversalBlocked("/%252e%252e/pom.xml");
    }

    @Test
    void testAbsolutePaths() throws Exception {
        assertTraversalBlocked("/C:/Windows/win.ini");
        assertTraversalBlocked("/etc/passwd");
    }

    @Test
    void testNullByte() throws Exception {
        assertTraversalBlocked("/index.html%00.txt");
    }
}
