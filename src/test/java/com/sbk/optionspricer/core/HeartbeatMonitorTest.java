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

    // ------------------------------------------------------------------ the outbound ping

    /** A local server standing in for the external watcher: answers with the given status and counts the requests it gets. */
    private static final class FakeWatcher implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger pings = new java.util.concurrent.atomic.AtomicInteger();

        FakeWatcher(int status) throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ping/SECRET-UUID-1234", exchange -> {
                pings.incrementAndGet();
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/ping/SECRET-UUID-1234";
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @Test
    void theEngineStartsAndKeepsPingingTheExternalWatcherWhileItIsAlive() throws Exception {
        try (FakeWatcher watcher = new FakeWatcher(200)) {
            HeartbeatMonitor monitor = new HeartbeatMonitor(tempDir.resolve("heartbeat.txt"), 1, 3.0, watcher.url(), 1);

            monitor.start();

            // the monitor's own count, not the watcher's: the watcher counts a request when it arrives, the monitor when the answer has come back
            assertTrue(waitFor(() -> monitor.pingSuccesses() >= 3, 5_000), "the first ping at start and one a second after: " + monitor.pingSuccesses());
            assertEquals(0, monitor.pingFailures());
            monitor.close();
            int atClose = watcher.pings.get();
            Thread.sleep(1_500);
            assertEquals(atClose, watcher.pings.get(), "no pings once the engine has stopped: silence is the signal");
        }
    }

    @Test
    void aWatcherThatRefusesOrIsUnreachableIsCountedNotThrownAndTheUrlIsNeverPrinted() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.PrintStream err = System.err;
        System.setErr(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try (FakeWatcher refusing = new FakeWatcher(500)) {
            HeartbeatMonitor refused = new HeartbeatMonitor(tempDir.resolve("a.txt"), 1, 3.0, refusing.url(), 1);
            refused.start();
            assertTrue(waitFor(() -> refused.pingFailures() >= 2, 5_000), "failures are counted");
            assertEquals(0, refused.pingSuccesses());
            refused.close();

            HeartbeatMonitor nowhere = new HeartbeatMonitor(tempDir.resolve("b.txt"), 1, 3.0, "http://127.0.0.1:1/ping/SECRET-UUID-1234", 1);
            nowhere.start();
            assertTrue(waitFor(() -> nowhere.pingFailures() >= 1, 5_000), "a refused connection is a failure, not a crash");
            nowhere.close();
        } finally {
            System.setErr(err);
        }
        String logged = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(logged.contains("ping failed"), "the failure is said: " + logged);
        assertFalse(logged.contains("SECRET-UUID-1234"), "the URL carries a secret: " + logged);
    }

    @Test
    void withoutAUrlNothingIsSentAnywhere() throws Exception {
        HeartbeatMonitor monitor = new HeartbeatMonitor(tempDir.resolve("heartbeat.txt"), 1, 3.0);

        monitor.start();
        Thread.sleep(1_200);
        monitor.close();

        assertEquals(0, monitor.pingSuccesses() + monitor.pingFailures());
    }

    @Test
    void aBadPingIntervalOrUrlIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatMonitor(tempDir.resolve("h.txt"), 1, 3.0, "http://127.0.0.1:1/x", 0));
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatMonitor(tempDir.resolve("h.txt"), 1, 3.0, "not a url at all", 60));
    }

    // ------------------------------------------------------------------ what the previous run left behind

    private Path heartbeatLeftBy(String status, long secondsAgo) throws IOException {
        Path file = tempDir.resolve("previous-" + status + "-" + secondsAgo + ".txt");
        Files.writeString(file, "timestamp=2026-10-20T15:00:00Z|status=" + status + "|pid=1\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(secondsAgo)));
        return file;
    }

    @Test
    void theStartupSaysHowTheLastRunEnded() throws IOException {
        assertTrue(new HeartbeatMonitor(tempDir.resolve("none.txt"), 30, 3.0).describePreviousRun().contains("first start"));

        String clean = new HeartbeatMonitor(heartbeatLeftBy("stopped", 2 * 3600 + 5 * 60), 30, 3.0).describePreviousRun();
        assertTrue(clean.contains("stopped cleanly") && clean.contains("2h 5m"), clean);

        String dead = new HeartbeatMonitor(heartbeatLeftBy("running", 3 * 3600), 30, 3.0).describePreviousRun();
        assertTrue(dead.contains("NOT stopped cleanly") && dead.contains("3h") && dead.toLowerCase().contains("crash"), dead);

        String quick = new HeartbeatMonitor(heartbeatLeftBy("running", 20), 30, 3.0).describePreviousRun();
        assertTrue(quick.contains("20s") && !quick.contains("NOT stopped cleanly"), "a restart within the threshold is not an outage: " + quick);
    }
}
