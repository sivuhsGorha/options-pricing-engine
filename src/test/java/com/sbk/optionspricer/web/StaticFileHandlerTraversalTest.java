package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StaticFileHandlerTraversalTest {

    @Test
    void testValidPath() throws Exception {
        assertTrue(OptionsDashboardServer.StaticFileHandler.isPathSafe("/index.html"), "Valid path should be allowed");
        assertTrue(OptionsDashboardServer.StaticFileHandler.isPathSafe("/css/style.css"), "Subdirectory path should be allowed");
    }

    @Test
    void testTraversalAttempts() throws Exception {
        assertFalse(OptionsDashboardServer.StaticFileHandler.isPathSafe("/../README.md"), "Path traversal should be rejected");
        assertFalse(OptionsDashboardServer.StaticFileHandler.isPathSafe("/css/../../pom.xml"), "Nested path traversal should be rejected");
        assertFalse(OptionsDashboardServer.StaticFileHandler.isPathSafe("/%2e%2e/README.md"), "Encoded path traversal should be rejected");
        assertFalse(OptionsDashboardServer.StaticFileHandler.isPathSafe("\\..\\README.md"), "Windows separator traversal should be rejected");
    }
}
