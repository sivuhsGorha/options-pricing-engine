package com.sbk.optionspricer.alert;

import com.sbk.optionspricer.config.EnvironmentConfigLoader;
import com.sbk.optionspricer.execution.TradingHalt;
import com.sbk.optionspricer.market.MarketDataStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Push notification service for critical operational alerts:
 * - Engine down / shutting down
 * - Trading halts (risk breach, position mismatch, manual halt)
 * - Market data stale for N minutes
 *
 * Supported transports:
 * 1. ntfy.sh: zero-setup push notifications to mobile/desktop (via NTFY_TOPIC).
 * 2. Generic HTTP webhook (via ALERT_WEBHOOK_URL).
 * If neither is configured, alerts are logged to stderr.
 */
public final class PushNotificationService implements AutoCloseable {

    private final String ntfyTopic;
    private final String webhookUrl;
    private final long staleAlertThresholdMs;
    private final HttpClient httpClient;
    private final AtomicLong lastStaleAlertAt = new AtomicLong(0L);
    private final AtomicBoolean registeredShutdownHook = new AtomicBoolean(false);
    private volatile boolean closed;

    private static final long STALE_ALERT_COOLDOWN_MS = 15 * 60 * 1000L; // alert at most once every 15m for staleness

    public PushNotificationService() {
        this(
                EnvironmentConfigLoader.get("NTFY_TOPIC"),
                EnvironmentConfigLoader.get("ALERT_WEBHOOK_URL"),
                Duration.ofMinutes(Long.parseLong(EnvironmentConfigLoader.getOrDefault("ALERT_STALE_MINUTES", "5"))),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        );
    }

    public PushNotificationService(String ntfyTopic, String webhookUrl, Duration staleThreshold, HttpClient httpClient) {
        this.ntfyTopic = ntfyTopic != null && !ntfyTopic.isBlank() ? ntfyTopic.trim() : null;
        this.webhookUrl = webhookUrl != null && !webhookUrl.isBlank() ? webhookUrl.trim() : null;
        this.staleAlertThresholdMs = Math.max(60_000L, staleThreshold != null ? staleThreshold.toMillis() : 300_000L);
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // Neither the topic nor the URL is printed: a webhook URL usually carries its own token, and anyone who
        // knows a public ntfy topic can read what is sent to it.
        if (this.ntfyTopic != null) {
            System.out.println("[ALERT] Push notifications enabled via ntfy.sh (NTFY_TOPIC is set; use a long random name, a public topic is readable by anyone who knows it)");
        }
        if (this.webhookUrl != null) {
            System.out.println("[ALERT] Push notifications enabled via webhook (ALERT_WEBHOOK_URL is set)");
        }
        if (this.ntfyTopic == null && this.webhookUrl == null) {
            System.out.println("[ALERT] Push notifications: local log only (set NTFY_TOPIC or ALERT_WEBHOOK_URL to enable mobile push)");
        }

        registerShutdownHook();
    }

    /** True when a topic or a webhook is configured, so a message reaches beyond the log. */
    public boolean hasRemoteChannel() {
        return ntfyTopic != null || webhookUrl != null;
    }

    /**
     * Attaches to a {@link TradingHalt} to immediately fire a notification when trading halts.
     */
    public void monitorTradingHalt(TradingHalt halt) {
        if (halt == null) return;
        // Check periodically or report when tripped
        Thread t = new Thread(() -> {
            // Start from "not halted": a halt already on when this attaches, or tripped before this thread's first look, is a change and is
            // reported; taking the state at that first look as the starting state would silently swallow it.
            boolean previouslyHalted = false;
            while (!closed) {
                try {
                    Thread.sleep(1000L);
                    boolean currentlyHalted = halt.isHalted();
                    if (currentlyHalted && !previouslyHalted) {
                        String reason = halt.reason().map(TradingHalt.Reason::message).orElse("unspecified");
                        sendNotification("TRADING HALTED", "Options trading halted: " + reason, "urgent", "warning");
                    }
                    previouslyHalted = currentlyHalted;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "alert-halt-monitor");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Checks quote age and fires an alert if market data has been STALE beyond the threshold.
     */
    public void checkStaleness(MarketDataStatus status, long quoteAgeMs, String symbol) {
        if (closed) return;
        boolean isStale = status == MarketDataStatus.STALE || quoteAgeMs >= staleAlertThresholdMs;
        if (!isStale) return;

        long now = System.currentTimeMillis();
        long last = lastStaleAlertAt.get();
        if (now - last >= STALE_ALERT_COOLDOWN_MS) {
            if (lastStaleAlertAt.compareAndSet(last, now)) {
                long mins = Math.max(1L, quoteAgeMs / 60_000L);
                sendNotification("MARKET DATA STALE",
                        String.format(Locale.ROOT, "Market data for %s is STALE (age %d min, limit %d min)",
                                symbol != null ? symbol : "SPY", mins, staleAlertThresholdMs / 60_000L),
                        "high", "clock");
            }
        }
    }

    /**
     * Sends an alert notification.
     *
     * @param title    alert title
     * @param message  alert body message
     * @param priority ntfy priority (e.g. "urgent", "high", "default", "low")
     * @param tags     optional comma-separated tags or emojis
     * @return true if transmitted successfully or logged
     */
    public boolean sendNotification(String title, String message, String priority, String tags) {
        System.err.println("[ALERT][" + priority.toUpperCase(Locale.ROOT) + "] " + title + ": " + message);

        boolean sent = false;
        if (ntfyTopic != null) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create("https://ntfy.sh/" + ntfyTopic))
                        .timeout(Duration.ofSeconds(5))
                        .header("Title", "[Blackthorn Sterling] " + title)
                        .header("Priority", priority != null ? priority : "default")
                        .POST(HttpRequest.BodyPublishers.ofString(message));
                if (tags != null && !tags.isBlank()) {
                    builder.header("Tags", tags);
                }
                HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                sent = resp.statusCode() >= 200 && resp.statusCode() < 300;
                if (!sent) {
                    System.err.println("[ALERT] ntfy.sh answered HTTP " + resp.statusCode() + ": the alert was not delivered");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[ALERT] ntfy alert not delivered: interrupted");
            } catch (IOException | RuntimeException e) {
                // the topic is not printed: a public topic is readable by anyone who knows its name
                System.err.println("[ALERT] ntfy alert not delivered: " + e.getClass().getSimpleName());
            }
        }

        if (webhookUrl != null) {
            try {
                String json = String.format(Locale.ROOT,
                        "{\"title\":\"[Blackthorn Sterling] %s\",\"message\":\"%s\",\"priority\":\"%s\",\"timestamp\":\"%s\"}",
                        escapeJson(title), escapeJson(message), priority, Instant.now());
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                sent = resp.statusCode() >= 200 && resp.statusCode() < 300;
                if (!sent) {
                    System.err.println("[ALERT] webhook answered HTTP " + resp.statusCode() + ": the alert was not delivered");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[ALERT] webhook alert not delivered: interrupted");
            } catch (IOException | RuntimeException e) {
                // the URL is not printed (it usually carries a secret token), and neither is the exception text, which can quote it
                System.err.println("[ALERT] webhook alert not delivered: " + e.getClass().getSimpleName());
            }
        }

        return sent;
    }

    private void registerShutdownHook() {
        if (registeredShutdownHook.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sendNotification("ENGINE STOPPED", "Blackthorn Sterling engine process stopped at " + Instant.now(), "high", "stop_sign");
            }, "alert-shutdown-notify"));
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
