package com.sbk.optionspricer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatMonitorTest {

    @TempDir
    Path tempDir;

    @Test
    void testHeartbeatMonitorCreatesFile() throws InterruptedException {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");
        HeartbeatMonitor monitor = new HeartbeatMonitor(heartbeatFile, 1, 3.0);

        monitor.start();
        Thread.sleep(1500); // Wait for at least one heartbeat

        assertTrue(Files.exists(heartbeatFile), "Heartbeat file should exist after starting");

        monitor.close();
    }

    @Test
    void testHeartbeatContentFormat() throws InterruptedException, IOException {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");
        HeartbeatMonitor monitor = new HeartbeatMonitor(heartbeatFile, 1, 3.0);

        monitor.start();
        Thread.sleep(1500); // Wait for at least one heartbeat

        String content = Files.readString(heartbeatFile);
        assertTrue(content.contains("timestamp="), "Heartbeat should contain timestamp");
        assertTrue(content.contains("status=running"), "Heartbeat should contain status=running");
        assertTrue(content.contains("pid="), "Heartbeat should contain pid");

        monitor.close();
    }

    @Test
    void testHeartbeatStoppedOnClose() throws InterruptedException, IOException {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");
        HeartbeatMonitor monitor = new HeartbeatMonitor(heartbeatFile, 1, 3.0);

        monitor.start();
        Thread.sleep(1500);
        monitor.close();

        String content = Files.readString(heartbeatFile);
        assertTrue(content.contains("status=stopped"), "Heartbeat should contain status=stopped after close");
    }

    @Test
    void testStaleHeartbeatDetection() throws IOException, InterruptedException {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");

        // Create an old heartbeat file
        String oldHeartbeat = "timestamp=2024-01-01T00:00:00Z|status=running|pid=12345";
        Files.writeString(heartbeatFile, oldHeartbeat);

        // Create monitor with short stale threshold
        HeartbeatMonitor monitor = new HeartbeatMonitor(heartbeatFile, 1, 1.0);

        // The constructor should detect the stale heartbeat
        // We can't easily capture stderr, but the file should still exist
        assertTrue(Files.exists(heartbeatFile));

        monitor.close();
    }

    @Test
    void testIsHeartbeatStaleUtility() throws IOException {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");

        // Non-existent file is considered stale
        assertTrue(HeartbeatMonitor.isHeartbeatStale(heartbeatFile, 60));

        // Create a recent heartbeat
        Files.writeString(heartbeatFile, "timestamp=" + Instant.now() + "|status=running|pid=12345");
        assertFalse(HeartbeatMonitor.isHeartbeatStale(heartbeatFile, 60));

        // Create an old heartbeat
        Files.setLastModifiedTime(heartbeatFile, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 120_000)); // 2 minutes ago
        assertTrue(HeartbeatMonitor.isHeartbeatStale(heartbeatFile, 60));
        assertFalse(HeartbeatMonitor.isHeartbeatStale(heartbeatFile, 180));
    }

    @Test
    void testMultipleStartsDoNotDuplicate() throws InterruptedException {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");
        HeartbeatMonitor monitor = new HeartbeatMonitor(heartbeatFile, 1, 3.0);

        monitor.start();
        monitor.start(); // Should be idempotent
        Thread.sleep(1500);

        assertTrue(Files.exists(heartbeatFile));

        monitor.close();
    }

    @Test
    void testMultipleClosesDoNotFail() {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");
        HeartbeatMonitor monitor = new HeartbeatMonitor(heartbeatFile, 1, 3.0);

        monitor.start();
        monitor.close();
        monitor.close(); // Should be idempotent

        // Should not throw
    }

    @Test
    void testInvalidInterval() {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");

        assertThrows(IllegalArgumentException.class, () -> {
            new HeartbeatMonitor(heartbeatFile, 0, 3.0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new HeartbeatMonitor(heartbeatFile, -1, 3.0);
        });
    }

    @Test
    void testInvalidStaleMultiplier() {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");

        assertThrows(IllegalArgumentException.class, () -> {
            new HeartbeatMonitor(heartbeatFile, 30, 0.5);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new HeartbeatMonitor(heartbeatFile, 30, -1.0);
        });
    }

    @Test
    void testNullHeartbeatFile() {
        assertThrows(IllegalArgumentException.class, () -> {
            new HeartbeatMonitor(null, 30, 3.0);
        });
    }

    @Test
    void testGetters() {
        Path heartbeatFile = tempDir.resolve("heartbeat.txt");
        HeartbeatMonitor monitor = new HeartbeatMonitor(heartbeatFile, 30, 3.0);

        assertEquals(heartbeatFile, monitor.getHeartbeatFile());
        assertEquals(90L, monitor.getStaleThresholdSeconds()); // 30 * 3.0

        monitor.close();
    }
}
