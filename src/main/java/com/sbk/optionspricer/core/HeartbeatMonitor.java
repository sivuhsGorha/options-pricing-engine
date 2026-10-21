package com.sbk.optionspricer.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p>The file is a record and alerts no one: it lives on the machine that would be down. What can alert while the machine is asleep or off is a
 * ping to a watcher outside it. With {@code HEARTBEAT_URL} set (a service such as healthchecks.io), the monitor sends it a GET every
 * {@code HEARTBEAT_PING_SECONDS} (default 60) while the engine is alive, and the service alerts when the pings stop. A refused or failed ping is
 * counted and logged without the URL (it carries a token) and never disturbs the engine; nothing is sent on a clean stop, because silence is the signal.
 */
public final class HeartbeatMonitor implements AutoCloseable {

    private final Path heartbeatFile;
    private final long intervalSeconds;
    private final long staleThresholdSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final URI pingUri;
    private final long pingSeconds;
    private final HttpClient http;
    private final ScheduledExecutorService pinger;
    private final AtomicLong pingSuccesses = new AtomicLong();
    private final AtomicLong pingFailures = new AtomicLong();
    private final AtomicLong consecutivePingFailures = new AtomicLong();
    private volatile String previousRun = "no information about the previous run";

    public HeartbeatMonitor() {
        this(
                Path.of(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("DATA_DIR", "data")).resolve("heartbeat.txt"),
                Long.parseLong(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("HEARTBEAT_INTERVAL_SECONDS", "30")),
                Double.parseDouble(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("HEARTBEAT_STALE_MULTIPLIER", "3.0")),
                com.sbk.optionspricer.config.EnvironmentConfigLoader.get("HEARTBEAT_URL"),
                Long.parseLong(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("HEARTBEAT_PING_SECONDS", "60"))
        );
    }

    public HeartbeatMonitor(Path heartbeatFile, long intervalSeconds, double staleMultiplier) {
        this(heartbeatFile, intervalSeconds, staleMultiplier, null, 60);
    }

    /** @param pingUrl an http(s) URL pinged every {@code pingSeconds} while the engine is alive; null or blank for none */
    public HeartbeatMonitor(Path heartbeatFile, long intervalSeconds, double staleMultiplier, String pingUrl, long pingSeconds) {
        if (pingUrl != null && !pingUrl.isBlank()) {
            if (pingSeconds < 1) {
                throw new IllegalArgumentException("pingSeconds must be at least 1");
            }
            URI parsed;
            try {
                parsed = URI.create(pingUrl.trim());
            } catch (IllegalArgumentException e) {
                // the message of the parse error quotes the URL, which carries a token
                throw new IllegalArgumentException("the heartbeat URL is not a valid URL");
            }
            if (parsed.getHost() == null || !("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))) {
                throw new IllegalArgumentException("the heartbeat URL must be an http or https URL");
            }
            this.pingUri = parsed;
        } else {
            this.pingUri = null;
        }
        this.pingSeconds = pingSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-ping");
            t.setDaemon(true);
            return t;
        });
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
            previousRun = "first start (no previous heartbeat on file)";
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
            boolean clean = content.contains("status=stopped");
            if (clean) {
                System.out.println("[HEARTBEAT] Previous shutdown was clean (status=stopped)");
            }
            previousRun = describe(clean, ageSeconds);
        } catch (IOException e) {
            System.err.println("[HEARTBEAT] Failed to check previous heartbeat: " + e.getMessage());
        }
    }

    private String describe(boolean clean, long ageSeconds) {
        if (clean) {
            return "previous run stopped cleanly " + age(ageSeconds) + " ago";
        }
        if (ageSeconds > staleThresholdSeconds) {
            return "previous run was NOT stopped cleanly: its last heartbeat was " + age(ageSeconds)
                    + " ago (a crash, a power loss or a sleeping machine)";
        }
        return "previous run ended " + age(ageSeconds) + " ago without a clean stop, within the " + staleThresholdSeconds
                + " s threshold (a quick restart)";
    }

    private static String age(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
    }

    /** How the previous run ended, from the heartbeat it left: for the message sent when the engine starts. */
    public String describePreviousRun() {
        return previousRun;
    }

    public long pingSuccesses() {
        return pingSuccesses.get();
    }

    public long pingFailures() {
        return pingFailures.get();
    }

    private void ping() {
        if (closed.get()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(pingUri).timeout(Duration.ofSeconds(5)).GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null) {
                pingFailed(com.sbk.optionspricer.util.SafeErrorReporting.errorTypeOnly(error.getCause() != null ? error.getCause() : error));
            } else if (response.statusCode() / 100 == 2) {
                long failedBefore = consecutivePingFailures.getAndSet(0);
                pingSuccesses.incrementAndGet();
                if (failedBefore > 0) {
                    System.out.println("[HEARTBEAT] ping working again after " + failedBefore + " failed");
                }
            } else {
                pingFailed("HTTP " + response.statusCode());
            }
        });
    }

    private void pingFailed(String what) {
        pingFailures.incrementAndGet();
        long n = consecutivePingFailures.incrementAndGet();
        if (n == 1 || n % 10 == 0) {
            // never the URL: it carries a token
            System.err.println("[HEARTBEAT] ping failed (" + what + "), " + n + " in a row; the external watcher alerts if the pings stop");
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

        if (pingUri != null) {
            pinger.scheduleAtFixedRate(this::ping, 0, pingSeconds, TimeUnit.SECONDS);
            System.out.printf("[HEARTBEAT] Pinging the external watcher every %d seconds (the URL is not printed)%n", pingSeconds);
        }
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
        pinger.shutdownNow();
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
