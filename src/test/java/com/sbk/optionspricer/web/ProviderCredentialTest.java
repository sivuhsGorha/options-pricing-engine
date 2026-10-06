package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCredentialTest {

    private record Captured(String url, Map<String, String> headers) {}

    private static LiveSpotProvider provider(String finnhub, String polygon, AtomicReference<Captured> sink) {
        return new LiveSpotProvider(finnhub, polygon, null, null, (url, headers) -> {
            sink.set(new Captured(url, headers));
            return "{\"c\":101.25}";
        });
    }

    @Test
    void finnhubKeyIsSentInAHeaderNotInTheUrl() {
        AtomicReference<Captured> sink = new AtomicReference<>();
        provider("finnhub-secret-key-123", null, sink).fetchFinnhub("SPY");

        Captured captured = sink.get();
        assertNotNull(captured, "the request must have been made");
        assertFalse(captured.url().contains("finnhub-secret-key-123"), "key must not appear in the URL: " + captured.url());
        assertEquals("finnhub-secret-key-123", captured.headers().get("X-Finnhub-Token"));
    }

    @Test
    void polygonKeyIsSentInTheAuthorizationHeaderNotInTheUrl() {
        AtomicReference<Captured> sink = new AtomicReference<>();
        provider(null, "polygon-secret-key-456", sink).fetchPolygonSnapshot("SPY");

        Captured captured = sink.get();
        assertNotNull(captured);
        assertFalse(captured.url().contains("polygon-secret-key-456"), captured.url());
        assertEquals("Bearer polygon-secret-key-456", captured.headers().get("Authorization"));
    }

    @Test
    void redactRemovesCredentialsFromUrlsBeforeTheyCanBeLogged() {
        String redacted = LiveSpotProvider.redact(
                "https://x.test/q?function=GLOBAL_QUOTE&symbol=SPY&apikey=abc123SECRET&access_key=zzz999&token=tok777&apiKey=K1");

        assertFalse(redacted.contains("abc123SECRET"), redacted);
        assertFalse(redacted.contains("zzz999"), redacted);
        assertFalse(redacted.contains("tok777"), redacted);
        assertFalse(redacted.contains("K1"), redacted);
        assertTrue(redacted.contains("symbol=SPY"), "non-secret parameters are kept: " + redacted);
        assertTrue(redacted.contains("apikey=***"), redacted);
    }

    @Test
    void redactAlsoCleansExceptionMessagesThatEmbedAUrl() {
        String message = "java.io.FileNotFoundException: https://api.marketstack.com/v1/eod/latest?access_key=TOPSECRET&symbols=SPY";
        assertFalse(LiveSpotProvider.redact(message).contains("TOPSECRET"));
        assertNull(LiveSpotProvider.redact(null));
    }
}
