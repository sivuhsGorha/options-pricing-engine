package com.sbk.optionspricer.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredLoggerTest {

    @Test
    void testBasicLogging() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        logger.info("Test message");

        String output = out.toString();
        assertTrue(output.contains("\"level\":\"INFO\""));
        assertTrue(output.contains("\"component\":\"TEST\""));
        assertTrue(output.contains("\"message\":\"Test message\""));
        assertTrue(output.contains("\"timestamp\""));
    }

    @Test
    void testLoggingWithData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key1", "value1");
        data.put("key2", 123);
        logger.info("Test message", data);

        String output = out.toString();
        assertTrue(output.contains("\"key1\":\"value1\""));
        assertTrue(output.contains("\"key2\":123"));
    }

    @Test
    void testLoggingWithException() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        Exception e = new RuntimeException("Test exception");
        logger.error("Error occurred", e);

        String output = out.toString();
        assertTrue(output.contains("\"level\":\"ERROR\""));
        assertTrue(output.contains("\"error\""));
        assertTrue(output.contains("\"type\":\"RuntimeException\""));
        assertTrue(output.contains("\"message\":\"Test exception\""));
    }

    @Test
    void testMinLevelFiltering() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        StructuredLogger.setGlobalMinLevel(StructuredLogger.Level.WARN);

        logger.debug("Debug message");
        logger.info("Info message");
        logger.warn("Warn message");
        logger.error("Error message");

        String output = out.toString();
        assertFalse(output.contains("Debug message"));
        assertFalse(output.contains("Info message"));
        assertTrue(output.contains("Warn message"));
        assertTrue(output.contains("Error message"));

        // Reset
        StructuredLogger.setGlobalMinLevel(StructuredLogger.Level.INFO);
    }

    @Test
    void testGlobalMinLevel() {
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        StructuredLogger logger1 = new StructuredLogger("TEST1", new PrintStream(out1));
        StructuredLogger logger2 = new StructuredLogger("TEST2", new PrintStream(out2));

        StructuredLogger.setGlobalMinLevel(StructuredLogger.Level.ERROR);

        logger1.info("Info 1");
        logger2.info("Info 2");
        logger1.error("Error 1");
        logger2.error("Error 2");

        String output1 = out1.toString();
        String output2 = out2.toString();

        assertFalse(output1.contains("Info 1"));
        assertFalse(output2.contains("Info 2"));
        assertTrue(output1.contains("Error 1"));
        assertTrue(output2.contains("Error 2"));

        // Reset
        StructuredLogger.setGlobalMinLevel(StructuredLogger.Level.INFO);
    }

    @Test
    void testContextFields() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        StructuredLogger.addContext("requestId", "12345");
        StructuredLogger.addContext("userId", "user1");

        logger.info("Test message");

        String output = out.toString();
        assertTrue(output.contains("\"context\""));
        assertTrue(output.contains("\"requestId\":\"12345\""));
        assertTrue(output.contains("\"userId\":\"user1\""));

        StructuredLogger.clearContext();
    }

    @Test
    void testContextIsolation() {
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        StructuredLogger logger1 = new StructuredLogger("TEST1", new PrintStream(out1));
        StructuredLogger logger2 = new StructuredLogger("TEST2", new PrintStream(out2));

        // Thread 1 context
        StructuredLogger.addContext("thread", "1");
        logger1.info("Message 1");

        // Clear and set for thread 2 (in same thread for test)
        StructuredLogger.clearContext();
        StructuredLogger.addContext("thread", "2");
        logger2.info("Message 2");

        String output1 = out1.toString();
        String output2 = out2.toString();

        assertTrue(output1.contains("\"thread\":\"1\""));
        assertTrue(output2.contains("\"thread\":\"2\""));
        assertFalse(output1.contains("\"thread\":\"2\""));
        assertFalse(output2.contains("\"thread\":\"1\""));

        StructuredLogger.clearContext();
    }

    @Test
    void testDifferentLevels() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        StructuredLogger.setGlobalMinLevel(StructuredLogger.Level.DEBUG);

        logger.debug("Debug");
        logger.info("Info");
        logger.warn("Warn");
        logger.error("Error");

        String output = out.toString();
        assertTrue(output.contains("\"level\":\"DEBUG\""));
        assertTrue(output.contains("\"level\":\"INFO\""));
        assertTrue(output.contains("\"level\":\"WARN\""));
        assertTrue(output.contains("\"level\":\"ERROR\""));

        // Reset
        StructuredLogger.setGlobalMinLevel(StructuredLogger.Level.INFO);
    }

    @Test
    void testForComponentFactory() {
        StructuredLogger logger = StructuredLogger.forComponent("TEST");
        assertNotNull(logger);
        // Can't easily test output without capturing System.out
    }

    @Test
    void testInvalidComponent() {
        assertThrows(IllegalArgumentException.class, () -> {
            new StructuredLogger("");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new StructuredLogger(null);
        });
    }

    @Test
    void testNullGlobalMinLevel() {
        assertThrows(IllegalArgumentException.class, () -> {
            StructuredLogger.setGlobalMinLevel(null);
        });
    }

    @Test
    void testJsonEscaping() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", "value with \"quotes\" and \\backslashes\\");
        logger.info("Test", data);

        String output = out.toString();
        assertTrue(output.contains("\\\""));
        assertTrue(output.contains("\\\\"));
    }

    @Test
    void testEmptyData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        logger.info("Test", new LinkedHashMap<>());

        String output = out.toString();
        // Should not include data field if empty
        assertFalse(output.contains("\"data\""));
    }

    @Test
    void testNullData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger logger = new StructuredLogger("TEST", new PrintStream(out));

        logger.info("Test", null);

        String output = out.toString();
        // Should not include data field if null
        assertFalse(output.contains("\"data\""));
    }
}
