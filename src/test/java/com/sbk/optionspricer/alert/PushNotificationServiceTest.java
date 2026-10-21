package com.sbk.optionspricer.alert;

import com.sbk.optionspricer.execution.TradingHalt;
import com.sbk.optionspricer.market.MarketDataStatus;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PushNotificationServiceTest {

    @Test
    void serviceConstructsWithNullTransportsAndLogsWithoutCrashing() {
        try (PushNotificationService service = new PushNotificationService(null, null, Duration.ofMinutes(5), null)) {
            // When no webhook or ntfy is configured, sendNotification logs to stderr without error
            boolean sent = service.sendNotification("TEST ALERT", "Test message", "low", "test");
            assertFalse(sent, "sent must be false when no remote transport is configured");
        }
    }

    @Test
    void theStartupLineNeverPrintsTheTopicOrTheWebhookUrl() {
        // A webhook URL usually carries its own secret token and a public ntfy topic is readable by anyone who knows
        // its name; the log is the last place either belongs.
        String topic = "bt-secret-topic-7f3a9c";
        String webhook = "https://hooks.example.com/services/T000/B000/SECRETTOKEN123";
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = System.out, err = System.err;
        java.io.PrintStream capture = new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try (PushNotificationService service = new PushNotificationService(topic, webhook, Duration.ofMinutes(5), null)) {
            capture.flush();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String logged = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(logged.contains("ntfy.sh") && logged.toLowerCase().contains("webhook"), "it still says what is enabled: " + logged);
        assertFalse(logged.contains(topic), logged);
        assertFalse(logged.contains("SECRETTOKEN123") || logged.contains(webhook), logged);
    }

    @Test
    void checkStalenessTriggersOnlyWhenStaleThresholdMet() {
        try (PushNotificationService service = new PushNotificationService(null, null, Duration.ofMinutes(5), null)) {
            // Fresh quote: age 10s -> should NOT alert
            service.checkStaleness(MarketDataStatus.LIVE, 10_000L, "SPY");

            // Stale quote: age 6 minutes (360_000 ms) -> should trigger alert
            service.checkStaleness(MarketDataStatus.LIVE, 360_000L, "SPY");

            // Cooldown check: second immediate call should be throttled by cooldown
            service.checkStaleness(MarketDataStatus.STALE, 370_000L, "SPY");
        }
    }

    @Test
    void monitorTradingHaltAcceptsTradingHaltAndClosesCleanly() {
        TradingHalt halt = new TradingHalt();
        try (PushNotificationService service = new PushNotificationService(null, null, Duration.ofMinutes(5), null)) {
            service.monitorTradingHalt(halt);
            assertFalse(halt.isHalted());
        }
    }

    // ------------------------------------------------------------------ delivery

    /** A local HTTP server standing in for the webhook: answers with the given status and remembers every body it was sent. */
    private static final class FakeWebhook implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();

        FakeWebhook(int status) throws java.io.IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook/SECRETTOKEN123", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/SECRETTOKEN123";
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    private static String captureStderr(Runnable action) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.PrintStream err = System.err;
        System.setErr(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(err);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void anAlertIsDeliveredToTheWebhookWithItsTitleAndMessage() throws Exception {
        try (FakeWebhook hook = new FakeWebhook(200);
             PushNotificationService service = new PushNotificationService(null, hook.url(), Duration.ofMinutes(5), null)) {

            boolean sent = service.sendNotification("TRADING HALTED", "book does not match", "urgent", "warning");

            assertTrue(sent);
            assertEquals(1, hook.bodies.size());
            assertTrue(hook.bodies.get(0).contains("TRADING HALTED") && hook.bodies.get(0).contains("book does not match"), hook.bodies.get(0));
        }
    }

    @Test
    void anAlertTheWebhookRefusesIsReportedAsNotDeliveredAndNeverPrintsTheUrl() throws Exception {
        // Found on 2026-10-21 reading the code: a non-2xx answer (a rate limit, a revoked token) was dropped without a word, so a halt
        // alert could be lost and the log would not say so.
        try (FakeWebhook hook = new FakeWebhook(500);
             PushNotificationService service = new PushNotificationService(null, hook.url(), Duration.ofMinutes(5), null)) {
            boolean[] sent = {true};

            String logged = captureStderr(() -> sent[0] = service.sendNotification("TRADING HALTED", "x", "urgent", "warning"));

            assertFalse(sent[0]);
            assertTrue(logged.contains("not delivered") && logged.contains("500"), logged);
            assertFalse(logged.contains("SECRETTOKEN123") || logged.contains(hook.url()), "the URL carries a token: " + logged);
        }
    }

    @Test
    void aWebhookThatIsNotAUrlDoesNotKillTheAlertThreadOrLeakItself() {
        try (PushNotificationService service = new PushNotificationService(null, "not a url SECRETTOKEN123", Duration.ofMinutes(5), null)) {
            boolean[] sent = {true};

            String logged = captureStderr(() -> sent[0] = service.sendNotification("TRADING HALTED", "x", "urgent", "warning"));

            assertFalse(sent[0]);
            assertTrue(logged.contains("not delivered"), logged);
            assertFalse(logged.contains("SECRETTOKEN123"), logged);
        }
    }

    @Test
    void aTradingHaltIsDeliveredWithinAFewSecondsOfTheHalt() throws Exception {
        TradingHalt halt = new TradingHalt();
        try (FakeWebhook hook = new FakeWebhook(200);
             PushNotificationService service = new PushNotificationService(null, hook.url(), Duration.ofMinutes(5), null)) {
            service.monitorTradingHalt(halt);

            halt.halt("book does not match the Alpaca account: SPY261120C00772000: local 3, alpaca 0");

            long deadline = System.currentTimeMillis() + 5_000;
            while (hook.bodies.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, hook.bodies.size(), "the halt alert reached the webhook");
            assertTrue(hook.bodies.get(0).contains("TRADING HALTED") && hook.bodies.get(0).contains("local 3, alpaca 0"), hook.bodies.get(0));
        }
    }

    @Test
    void aHaltThatIsAlreadyOnWhenTheMonitorAttachesIsStillReported() throws Exception {
        // Found when this test's neighbour failed on a slower machine in CI: the monitor took "was it halted?" as its starting state inside
        // its own thread, so a halt that happened before that first read was the starting state and was never reported.
        TradingHalt halt = new TradingHalt();
        halt.halt("book does not match the Alpaca account: SPY261120C00772000: local 3, alpaca 0");
        try (FakeWebhook hook = new FakeWebhook(200);
             PushNotificationService service = new PushNotificationService(null, hook.url(), Duration.ofMinutes(5), null)) {

            service.monitorTradingHalt(halt);

            long deadline = System.currentTimeMillis() + 5_000;
            while (hook.bodies.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, hook.bodies.size(), "the standing halt was reported once");
            assertTrue(hook.bodies.get(0).contains("TRADING HALTED") && hook.bodies.get(0).contains("local 3, alpaca 0"), hook.bodies.get(0));
            Thread.sleep(2_500);
            assertEquals(1, hook.bodies.size(), "and not again on every poll while it stays on");
        }
    }
}
