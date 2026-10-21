package com.sbk.optionspricer.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything the engine prints passes through one place before it leaves: the exact value of every configured secret is replaced, and the line can
 * be turned into JSON. The engine has 236 print sites and 64 places that print an exception's message, any of which can quote a key or a URL; this
 * does not depend on each of them being right.
 */
class ConsoleGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    private static final List<String> SECRETS = List.of("hooks.example.com/T000/SECRETTOKEN123", "ntfy-secret-topic-9f8e7d");

    private static final class Capture {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        List<String> lines() {
            String t = text();
            return t.isEmpty() ? List.of() : List.of(t.split("\\R"));
        }
    }

    // ------------------------------------------------------------------ redaction

    @Test
    void everyOccurrenceOfAConfiguredSecretIsReplacedAndShortOrBlankValuesAreIgnored() {
        List<String> secrets = List.of("abcdef123456", "short", "", "  ");

        String out = ConsoleGuard.redact("a abcdef123456 b abcdef123456 c short d", secrets);

        assertEquals("a *** b *** c short d", out, "'short' is under 8 characters: redacting it would mangle ordinary words");
    }

    @Test
    void theLongerSecretIsReplacedFirstSoAShorterOneInsideItCannotLeaveAFragment() {
        List<String> secrets = List.of("tokenvalue", "tokenvalue-with-a-suffix-12345");

        assertEquals("x *** y", ConsoleGuard.redact("x tokenvalue-with-a-suffix-12345 y", secrets));
    }

    @Test
    void theSecretsComeFromTheEnvironmentByName() {
        Map<String, String> env = Map.of(
                "ALPACA_SECRET", "alpaca-secret-value-1", "API_SECRET", "api-secret-value-2", "OPERATOR_PASSWORD", "operator-password-3",
                "NTFY_TOPIC", "ntfy-topic-value-4", "ALERT_WEBHOOK_URL", "https://hooks.example.com/x/secret-5", "HEARTBEAT_URL", "https://hc.example.com/ping/uuid-6",
                "FINNHUB_KEY", "short", "TLS_KEYSTORE_PASSWORD", "keystore-password-7");

        List<String> secrets = ConsoleGuard.secretsFrom(env::get);

        for (String expected : List.of("alpaca-secret-value-1", "api-secret-value-2", "operator-password-3", "ntfy-topic-value-4",
                "https://hooks.example.com/x/secret-5", "https://hc.example.com/ping/uuid-6", "keystore-password-7")) {
            assertTrue(secrets.contains(expected), expected + " in " + secrets);
        }
        assertFalse(secrets.contains("short"), "under 8 characters is not treated as a secret");
        assertFalse(secrets.contains(null));
    }

    @Test
    void aSecretInAnyPrintedLineIsReplacedWhateverCallPrintedIt() {
        Capture capture = new Capture();
        PrintStream guarded = ConsoleGuard.wrap(capture.stream, "stderr", SECRETS, false, CLOCK);

        guarded.println("[ALERT] failed to reach https://hooks.example.com/T000/SECRETTOKEN123 now");
        guarded.print("partial ");
        guarded.print("ntfy-secret");
        guarded.print("-topic-9f8e7d");   // the secret arrives in three pieces
        guarded.println(" end");
        guarded.printf("%s: %s%n", "[HTTP]", new IllegalStateException("bad webhook hooks.example.com/T000/SECRETTOKEN123"));

        String all = capture.text();
        assertFalse(all.contains("SECRETTOKEN123") || all.contains("ntfy-secret-topic-9f8e7d"), all);
        assertTrue(all.contains("[ALERT] failed to reach https://*** now"), all);
        assertTrue(all.contains("partial *** end"), "a secret split across print calls is still caught: " + all);
    }

    @Test
    void aStackTraceWhoseMessageQuotesASecretIsRedactedLineByLine() {
        Capture capture = new Capture();
        PrintStream guarded = ConsoleGuard.wrap(capture.stream, "stderr", SECRETS, false, CLOCK);

        new RuntimeException("rejected hooks.example.com/T000/SECRETTOKEN123", new IllegalArgumentException("ntfy-secret-topic-9f8e7d")).printStackTrace(guarded);

        assertFalse(capture.text().contains("SECRETTOKEN123") || capture.text().contains("ntfy-secret-topic-9f8e7d"), capture.text());
        assertTrue(capture.text().contains("RuntimeException") && capture.text().contains("Caused by"), "the trace itself is kept: " + capture.text());
    }

    // ------------------------------------------------------------------ JSON

    @Test
    void inJsonModeEachLineBecomesOneJsonObjectWithItsTagAsTheComponentAndAnImpliedLevel() throws Exception {
        Capture capture = new Capture();
        PrintStream out = ConsoleGuard.wrap(capture.stream, "stdout", SECRETS, true, CLOCK);
        PrintStream err = ConsoleGuard.wrap(capture.stream, "stderr", SECRETS, true, CLOCK);

        out.println("[RECONCILE] OK: book matches the Alpaca account (1 open positions)");
        err.println("[RISK BLOCK] Spread too wide: bid=12.50 ask=12.90 (315.0 bps > 200.0)");
        err.println("[ALERT][URGENT] TRADING HALTED: book does not match");
        out.println("a line with no tag at all");

        List<String> lines = capture.lines();
        assertEquals(4, lines.size(), capture.text());
        JsonNode reconcile = JSON.readTree(lines.get(0));
        assertEquals("2026-10-21T10:00:00Z", reconcile.get("timestamp").asText());
        assertEquals("INFO", reconcile.get("level").asText());
        assertEquals("RECONCILE", reconcile.get("component").asText());
        assertEquals("OK: book matches the Alpaca account (1 open positions)", reconcile.get("message").asText());
        assertEquals("stdout", reconcile.get("stream").asText());
        JsonNode risk = JSON.readTree(lines.get(1));
        assertEquals("WARN", risk.get("level").asText());
        assertEquals("RISK BLOCK", risk.get("component").asText());
        assertEquals("stderr", risk.get("stream").asText());
        JsonNode halt = JSON.readTree(lines.get(2));
        assertEquals("ERROR", halt.get("level").asText(), "an urgent alert is an error");
        assertEquals("ALERT", halt.get("component").asText());
        assertTrue(halt.get("message").asText().contains("TRADING HALTED"));
        JsonNode untagged = JSON.readTree(lines.get(3));
        assertEquals("console", untagged.get("component").asText());
        assertEquals("a line with no tag at all", untagged.get("message").asText());
    }

    @Test
    void quotesBackslashesAndNonAsciiSurviveTheJsonAndASecretIsRedactedBeforeItIsEncoded() throws Exception {
        Capture capture = new Capture();
        PrintStream out = ConsoleGuard.wrap(capture.stream, "stdout", SECRETS, true, CLOCK);
        String original = "[STRATEGY] note \"quoted\" path C:\\data and \u00b7 dot, secret ntfy-secret-topic-9f8e7d";

        out.println(original);

        JsonNode node = JSON.readTree(capture.text().trim());
        assertEquals("note \"quoted\" path C:\\data and \u00b7 dot, secret ***", node.get("message").asText());
    }

    @Test
    void aLineStillBeingWrittenWhenTheProcessEndsIsNotLost() {
        Capture capture = new Capture();
        ConsoleGuard.Guarded guarded = ConsoleGuard.wrap(capture.stream, "stdout", SECRETS, false, CLOCK);

        guarded.print("last words with no newline ntfy-secret-topic-9f8e7d");
        assertEquals("", capture.text(), "held until the line ends");
        guarded.flushPending();

        assertEquals("last words with no newline ***", capture.text().trim());
    }
}
