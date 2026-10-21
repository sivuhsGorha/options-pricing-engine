package com.sbk.optionspricer.logging;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogTest {

    @Test
    void testTraditionalLogging() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            Log.setStructuredLogging(false);
            Log.info("TEST", "Test message");

            String output = out.toString();
            assertTrue(output.contains("[TEST] Test message"));
            assertFalse(output.contains("{")); // Not JSON
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void testStructuredLogging() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            Log.setStructuredLogging(true);
            Log.info("TEST", "Test message");

            String output = out.toString();
            assertTrue(output.contains("\"level\":\"INFO\""));
            assertTrue(output.contains("\"component\":\"TEST\""));
            assertTrue(output.contains("\"message\":\"Test message\""));
        } finally {
            System.setOut(originalOut);
            Log.setStructuredLogging(false);
        }
    }

    @Test
    void testStructuredLoggingWithData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            Log.setStructuredLogging(true);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("key", "value");
            Log.info("TEST", "Test message", data);

            String output = out.toString();
            assertTrue(output.contains("\"data\""));
            assertTrue(output.contains("\"key\":\"value\""));
        } finally {
            System.setOut(originalOut);
            Log.setStructuredLogging(false);
        }
    }

    @Test
    void testErrorLogging() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));

        try {
            Log.setStructuredLogging(false);
            Log.error("TEST", "Error message");

            String output = err.toString();
            assertTrue(output.contains("[TEST] Error message"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testErrorLoggingWithException() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));

        try {
            Log.setStructuredLogging(true);
            Exception e = new RuntimeException("Test exception");
            Log.error("TEST", "Error message", e);

            String output = err.toString();
            assertTrue(output.contains("\"error\""));
            assertTrue(output.contains("\"type\":\"RuntimeException\""));
        } finally {
            System.setErr(originalErr);
            Log.setStructuredLogging(false);
        }
    }

    @Test
    void testDebugLogging() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            Log.setStructuredLogging(false);
            Log.debug("TEST", "Debug message");

            String output = out.toString();
            // Debug logs are suppressed in traditional mode
            assertTrue(output.isEmpty());
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void testDebugLoggingStructured() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            Log.setStructuredLogging(true);
            Log.setMinLevel(StructuredLogger.Level.DEBUG);
            Log.debug("TEST", "Debug message");

            String output = out.toString();
            assertTrue(output.contains("\"level\":\"DEBUG\""));
        } finally {
            System.setOut(originalOut);
            Log.setMinLevel(StructuredLogger.Level.INFO);
            Log.setStructuredLogging(false);
        }
    }

    @Test
    void testMinLevel() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));

        try {
            Log.setStructuredLogging(true);
            Log.setMinLevel(StructuredLogger.Level.WARN);

            Log.info("TEST", "Info");
            Log.warn("TEST", "Warn");
            Log.error("TEST", "Error");

            String output = out.toString();
            String error = err.toString();
            // Info should be filtered out
            assertFalse(output.contains("Info") && !error.contains("Info"));
            // Warn and Error should be present (they go to stderr in structured mode)
            assertTrue(error.contains("Warn"));
            assertTrue(error.contains("Error"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            Log.setMinLevel(StructuredLogger.Level.INFO);
            Log.setStructuredLogging(false);
        }
    }

    @Test
    void testContextFields() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            Log.setStructuredLogging(true);
            Log.addContext("requestId", "12345");
            Log.info("TEST", "Test message");

            String output = out.toString();
            assertTrue(output.contains("\"context\""));
            assertTrue(output.contains("\"requestId\":\"12345\""));
        } finally {
            System.setOut(originalOut);
            Log.clearContext();
            Log.setStructuredLogging(false);
        }
    }

    @Test
    void testNullMinLevel() {
        assertThrows(IllegalArgumentException.class, () -> {
            Log.setMinLevel(null);
        });
    }

    @Test
    void testIsStructuredLoggingEnabled() {
        Log.setStructuredLogging(true);
        assertTrue(Log.isStructuredLoggingEnabled());

        Log.setStructuredLogging(false);
        assertFalse(Log.isStructuredLoggingEnabled());
    }
}
