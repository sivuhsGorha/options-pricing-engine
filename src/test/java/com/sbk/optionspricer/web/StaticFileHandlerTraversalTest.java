package com.sbk.optionspricer.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StaticFileHandlerTraversalTest {

    private static HttpServer server;
    private static final int PORT = 8086;

    @BeforeAll
    static void startServer() throws Exception {
        // Create a dummy web dir with index.html to ensure server serves files
        new File("web").mkdirs();
        try (FileOutputStream out = new FileOutputStream("web/index.html")) {
            out.write("hello".getBytes());
        }

        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new OptionsDashboardServer.StaticFileHandler());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
        new File("web/index.html").delete();
        new File("web").delete();
    }

    private void assertTraversalBlocked(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + PORT + path).openConnection();
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
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + PORT + "/index.html").openConnection();
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
