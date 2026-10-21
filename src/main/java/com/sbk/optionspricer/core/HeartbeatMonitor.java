package com.sbk.optionspricer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Heartbeat monitor that periodically updates a heartbeat file to detect crashes, power loss,
 * or laptop standby. The heartbeat file is updated every interval with the current timestamp.
 * An external process can check the file's last modified time to determine if the engine is alive.
 *
 * Features:
 * - Periodic heartbeat file updates (default every 30 seconds)
 * - Configurable heartbeat interval via HEARTBEAT_INTERVAL_SECONDS environment variable
 * - Graceful shutdown writes a final heartbeat with status "stopped"
 * - Startup check reports if previous heartbeat was stale (crash/standby detected)
 * - Stale threshold is 3x the heartbeat interval (configurable via HEARTBEAT_STALE_MULTIPLIER)
 */
public final class HeartbeatMonitor implements AutoCloseable {

    private final Path heartbeatFile;
    private final long intervalSeconds;
    private final long staleThresholdSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public HeartbeatMonitor() {
        this(
                Path.of(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("DATA_DIR", "data")).resolve("heartbeat.txt"),
                Long.parseLong(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("HEARTBEAT_INTERVAL_SECONDS", "30")),
                Double.parseDouble(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("HEARTBEAT_STALE_MULTIPLIER", "3.0"))
        );
    }

    public HeartbeatMonitor(Path heartbeatFile, long intervalSeconds, double staleMultiplier) {
        if (heartbeatFile == null) {
            throw new IllegalArgumentException("heartbeatFile must not be null");
        }
        if (intervalSeconds < 1) {
            throw new IllegalArgumentException("intervalSeconds must be at least 1");
        }
        if (staleMultiplier < 1.0) {
            throw new IllegalArgumentException("staleMultiplier must be at least 1.0");
        }

        this.heartbeatFile = heartbeatFile;
        this.intervalSeconds = intervalSeconds;
        this.staleThresholdSeconds = (long) (intervalSeconds * staleMultiplier);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });

        // Ensure parent directory exists
        try {
            Files.createDirectories(heartbeatFile.getParent());
        } catch (IOException e) {
            System.err.println("[HEARTBEAT] Failed to create heartbeat directory: " + e.getMessage());
        }

        // Check if previous heartbeat was stale
        checkPreviousHeartbeat();
    }

    /**
     * Checks the previous heartbeat file and reports if it was stale.
     * A stale heartbeat indicates a crash, power loss, or extended standby.
     */
    private void checkPreviousHeartbeat() {
        if (!Files.exists(heartbeatFile)) {
            System.out.println("[HEARTBEAT] No previous heartbeat found (first start or file deleted)");
            return;
        }

        try {
            FileTime lastModified = Files.getLastModifiedTime(heartbeatFile);
            long ageSeconds = (System.currentTimeMillis() - lastModified.toMillis()) / 1000;

            if (ageSeconds > staleThresholdSeconds) {
                System.err.printf("[HEARTBEAT] WARNING: Previous heartbeat was stale (age %d seconds, threshold %d seconds). "
                                + "This indicates a crash, power loss, or extended standby since %s%n",
                        ageSeconds, staleThresholdSeconds, Instant.ofEpochMilli(lastModified.toMillis()));
            } else {
                System.out.printf("[HEARTBEAT] Previous heartbeat was recent (age %d seconds, within threshold %d seconds)%n",
                        ageSeconds, staleThresholdSeconds);
            }

            // Read previous status if available
            String content = Files.readString(heartbeatFile).trim();
            if (content.contains("status=stopped")) {
                System.out.println("[HEARTBEAT] Previous shutdown was clean (status=stopped)");
            }
        } catch (IOException e) {
            System.err.println("[HEARTBEAT] Failed to check previous heartbeat: " + e.getMessage());
        }
    }

    /**
     * Starts the heartbeat monitor. Should be called after the engine is fully initialized.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return; // already started
        }

        // Write initial heartbeat
        writeHeartbeat("running");

        // Schedule periodic heartbeat updates
        scheduler.scheduleAtFixedRate(
                () -> {
                    if (!closed.get()) {
                        writeHeartbeat("running");
                    }
                },
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        System.out.printf("[HEARTBEAT] Monitor started (interval: %d seconds, stale threshold: %d seconds, file: %s)%n",
                intervalSeconds, staleThresholdSeconds, heartbeatFile);
    }

    /**
     * Writes a heartbeat entry with the current timestamp and status.
     *
     * @param status the status (e.g., "running", "stopped")
     */
    private void writeHeartbeat(String status) {
        try {
            String heartbeat = String.format("timestamp=%s|status=%s|pid=%d%n",
                    Instant.now().toString(),
                    status,
                    ProcessHandle.current().pid());
            Files.writeString(heartbeatFile, heartbeat,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC);
        } catch (IOException e) {
            System.err.println("[HEARTBEAT] Failed to write heartbeat: " + e.getMessage());
        }
    }

    /**
     * Stops the heartbeat monitor and writes a final heartbeat with status "stopped".
     * This allows external monitoring to distinguish between a clean shutdown and a crash.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // already closed
        }

        running.set(false);
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Write final heartbeat with stopped status
        writeHeartbeat("stopped");
        System.out.println("[HEARTBEAT] Monitor stopped (final heartbeat written with status=stopped)");
    }

    /**
     * Returns the path to the heartbeat file for external monitoring.
     *
     * @return the heartbeat file path
     */
    public Path getHeartbeatFile() {
        return heartbeatFile;
    }

    /**
     * Returns the stale threshold in seconds for external monitoring.
     *
     * @return the stale threshold in seconds
     */
    public long getStaleThresholdSeconds() {
        return staleThresholdSeconds;
    }

    /**
     * Static utility method to check if a heartbeat file is stale.
     * Useful for external monitoring scripts.
     *
     * @param heartbeatFile the heartbeat file to check
     * @param staleThresholdSeconds the stale threshold in seconds
     * @return true if the heartbeat is stale or the file doesn't exist
     */
    public static boolean isHeartbeatStale(Path heartbeatFile, long staleThresholdSeconds) {
        if (!Files.exists(heartbeatFile)) {
            return true;
        }

        try {
            FileTime lastModified = Files.getLastModifiedTime(heartbeatFile);
            long ageSeconds = (System.currentTimeMillis() - lastModified.toMillis()) / 1000;
            return ageSeconds > staleThresholdSeconds;
        } catch (IOException e) {
            return true; // assume stale if we can't read it
        }
    }
}
