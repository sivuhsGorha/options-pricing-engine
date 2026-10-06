package com.sbk.optionspricer.web;

import com.sbk.optionspricer.market.MarketDataStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Quote correctness: right symbol, real timestamps, robust parsing, honest fallbacks. */
class LiveSpotProviderQuoteTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static LiveSpotProvider finnhub(LiveSpotProvider.HttpGetter http) {
        return new LiveSpotProvider("fh-key", null, null, null, http);
    }

    private static long closeOf(LocalDate day) {
        return day.atTime(LocalTime.of(16, 0)).atZone(NEW_YORK).toInstant().toEpochMilli();
    }

    // ---------- 4.1 cache ----------

    @Test
    void cachedQuoteIsNeverReturnedForADifferentSymbol() {
        LiveSpotProvider provider = finnhub((url, headers) -> {
            if (url.contains("symbol=SPY")) return "{\"c\":500.00,\"t\":" + System.currentTimeMillis() / 1000 + "}";
            if (url.contains("symbol=QQQ")) return "{\"c\":400.00,\"t\":" + System.currentTimeMillis() / 1000 + "}";
            throw new IOException("unexpected " + url);
        });

        assertEquals(500.00, provider.getQuote("SPY").last(), 1e-9);
        LiveSpotProvider.Quote qqq = provider.getQuote("QQQ");

        assertEquals("QQQ", qqq.symbol());
        assertEquals(400.00, qqq.last(), 1e-9, "a SPY quote must not be served for a QQQ request");
    }

    @Test
    void freshCacheEntriesAvoidRefetchingPerSymbol() {
        AtomicInteger calls = new AtomicInteger();
        LiveSpotProvider provider = finnhub((url, headers) -> {
            calls.incrementAndGet();
            return "{\"c\":500.00,\"t\":" + System.currentTimeMillis() / 1000 + "}";
        });

        provider.getQuote("SPY");
        provider.getQuote("SPY");
        provider.getQuote("SPY");

        assertEquals(1, calls.get());
    }

    @Test
    void staleFallbackBelongsToTheRequestedSymbolOnly() {
        long now = System.currentTimeMillis() / 1000;
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(false);
        LiveSpotProvider provider = finnhub((url, headers) -> {
            if (failing.get()) throw new IOException("down");
            return "{\"c\":500.00,\"t\":" + now + "}";
        });
        provider.getQuote("SPY");
        failing.set(true);

        LiveSpotProvider.Quote qqq = provider.getQuote("QQQ");
        assertEquals(MarketDataStatus.UNAVAILABLE, qqq.status(), "QQQ was never fetched, so there is nothing stale to serve");
        assertTrue(Double.isNaN(qqq.last()));
    }

    // ---------- 4.2 real timestamps ----------

    @Test
    void finnhubTimestampComesFromTheResponseNotFromTheClock() {
        LiveSpotProvider.Quote quote = finnhub((url, headers) -> "{\"c\":101.25,\"t\":1700000000}").fetchFinnhub("SPY");

        assertNotNull(quote);
        assertEquals(1_700_000_000_000L, quote.timestamp());
    }

    @Test
    void aQuoteWithoutATimestampHasUnknownAgeAndIsNotPassedOffAsCurrent() {
        LiveSpotProvider.Quote quote = finnhub((url, headers) -> "{\"c\":101.25}").fetchFinnhub("SPY");

        assertNotNull(quote);
        assertEquals(0L, quote.timestamp(), "unknown time must read as 'very old', never as 'now'");
    }

    @Test
    void alphaVantageTimestampIsTheCloseOfItsLatestTradingDay() {
        String body = "{\"Global Quote\":{\"01. symbol\":\"SPY\",\"05. price\":\"450.1200\",\"07. latest trading day\":\"2024-01-05\"}}";
        LiveSpotProvider.Quote quote = new LiveSpotProvider(null, null, "av-key", null, (u, h) -> body).fetchAlphaVantage("SPY");

        assertNotNull(quote);
        assertEquals(450.12, quote.last(), 1e-9);
        assertEquals(closeOf(LocalDate.of(2024, 1, 5)), quote.timestamp());
    }

    @Test
    void alphaVantageRateLimitNoticeIsNotAQuote() {
        String body = "{\"Note\":\"Thank you for using Alpha Vantage! Our standard API call frequency is 5 calls per minute.\"}";
        assertNull(new LiveSpotProvider(null, null, "av-key", null, (u, h) -> body).fetchAlphaVantage("SPY"));
        String info = "{\"Information\":\"The standard API rate limit is 25 requests per day.\"}";
        assertNull(new LiveSpotProvider(null, null, "av-key", null, (u, h) -> info).fetchAlphaVantage("SPY"));
    }

    @Test
    void marketstackTimestampIsTheCloseOfTheBarDate() {
        String body = "{\"data\":[{\"symbol\":\"SPY\",\"close\":450.50,\"date\":\"2024-01-05T00:00:00+0000\"}]}";
        LiveSpotProvider.Quote quote = new LiveSpotProvider(null, null, null, "ms-key", (u, h) -> body).fetchMarketstack("SPY");

        assertNotNull(quote);
        assertEquals(450.50, quote.last(), 1e-9);
        assertEquals(closeOf(LocalDate.of(2024, 1, 5)), quote.timestamp());
    }

    @Test
    void polygonQuoteTimeIsNormalisedFromNanosMicrosMillisAndSeconds() {
        long ms = 1_700_000_000_123L;
        for (long raw : new long[]{ms * 1_000_000L, ms * 1_000L, ms}) {
            LiveSpotProvider.Quote quote = LiveSpotProvider.parsePolygonSnapshot("SPY", polygon(100.0, 100.1, 100.05, raw));
            assertNotNull(quote);
            assertEquals(ms, quote.timestamp(), "raw timestamp " + raw);
        }
        assertEquals(1_700_000_000_000L,
                LiveSpotProvider.parsePolygonSnapshot("SPY", polygon(100.0, 100.1, 100.05, 1_700_000_000L)).timestamp());
    }

    // ---------- 4.5 parsing ----------

    @Test
    void polygonWithoutALastTradeUsesTheMidpointInsteadOfPretendingTheBidTraded() {
        String body = "{\"ticker\":{\"lastQuote\":{\"p\":100.0,\"P\":100.2,\"t\":1700000000123000000}}}";
        LiveSpotProvider.Quote quote = LiveSpotProvider.parsePolygonSnapshot("SPY", body);

        assertNotNull(quote);
        assertEquals(100.1, quote.last(), 1e-9);
    }

    @Test
    void parsingFollowsTheJsonStructureNotTheFirstTextMatch() {
        // A nested "c" appears before the real top-level "c"; a regex would read 1.0.
        String body = "{\"meta\":{\"c\":1.0,\"t\":5},\"c\":101.25,\"t\":1700000000}";
        LiveSpotProvider.Quote quote = finnhub((url, headers) -> body).fetchFinnhub("SPY");

        assertEquals(101.25, quote.last(), 1e-9);
        assertEquals(1_700_000_000_000L, quote.timestamp());
    }

    @Test
    void polygonSnapshotWithNestedObjectsInsideLastQuoteStillParses() {
        String body = "{\"ticker\":{\"lastQuote\":{\"p\":100.0,\"P\":100.2,\"t\":1700000000123000000,\"c\":{\"x\":[1,2]}},"
                + "\"lastTrade\":{\"p\":100.15,\"t\":1700000000100000000},\"min\":{\"v\":4321}}}";
        LiveSpotProvider.Quote quote = LiveSpotProvider.parsePolygonSnapshot("SPY", body);

        assertNotNull(quote);
        assertEquals(100.15, quote.last(), 1e-9);
        assertEquals(4321L, quote.volume());
    }

    @Test
    void malformedAndEmptyPayloadsYieldNoQuoteRatherThanAnException() {
        for (String body : new String[]{"", "not json", "{}", "[]", "{\"c\":\"abc\"}", "{\"c\":-5}", "{\"c\":0}"}) {
            assertNull(finnhub((url, headers) -> body).fetchFinnhub("SPY"), "body: " + body);
        }
    }

    private static String polygon(double bid, double ask, double last, long quoteTime) {
        return "{\"ticker\":{\"lastQuote\":{\"p\":" + bid + ",\"P\":" + ask + ",\"t\":" + quoteTime + "},"
                + "\"lastTrade\":{\"p\":" + last + "}}}";
    }
}
