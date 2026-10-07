package com.sbk.optionspricer.market;

import com.sbk.optionspricer.web.LiveSpotProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LiveMarketSnapshotAdapterTest {

    @Test
    void returnsLiveSnapshotWhenProviderHasQuote() {
        LiveSpotProvider provider = new LiveSpotProvider(
                "demo-key",
                "demo-key",
                null,
                null,
                (url, headers) -> "{\"lastQuote\": {\"p\": 123.45, \"P\": 123.55, \"t\": " + System.currentTimeMillis() + "}}"
        );

        LiveMarketSnapshotAdapter adapter = new LiveMarketSnapshotAdapter(provider, 0.01d);

        MarketSnapshot snapshot = adapter.getSnapshot("SPY");

        assertEquals("SPY", snapshot.symbol());
        assertEquals("POLYGON", snapshot.source());
        assertEquals(MarketDataStatus.LIVE, snapshot.status());
        assertTrue(snapshot.ask() > snapshot.bid());
        assertFalse(snapshot.hasVolume(), "this snapshot carries no volume field, so none may be reported");
        assertNotNull(snapshot.timestamp());
    }

    private static LiveMarketSnapshotAdapter adapterFor(String finnhubBody) {
        LiveSpotProvider provider = new LiveSpotProvider("fh", null, null, null, (url, headers) -> finnhubBody);
        return new LiveMarketSnapshotAdapter(provider, 0.01d);
    }

    @Test
    void aFreshQuoteKeepsItsProviderStatusAndReportsItsRealAge() {
        long ts = System.currentTimeMillis() / 1000 - 5;
        MarketSnapshot snapshot = adapterFor("{\"c\":101.25,\"t\":" + ts + "}").getSnapshot("SPY");

        assertEquals(MarketDataStatus.DELAYED, snapshot.status());
        assertTrue(snapshot.derivedQuoteAgeMs() >= 4_000 && snapshot.derivedQuoteAgeMs() < 15_000,
                "age must come from the provider timestamp: " + snapshot.derivedQuoteAgeMs());
    }

    @Test
    void anyQuoteOlderThanTheFreshnessLimitIsStaleWhateverItsLabel() {
        long twoHoursAgo = System.currentTimeMillis() / 1000 - 7_200;
        MarketSnapshot snapshot = adapterFor("{\"c\":101.25,\"t\":" + twoHoursAgo + "}").getSnapshot("SPY");

        assertEquals(MarketDataStatus.STALE, snapshot.status(), "a DELAYED-labelled quote from hours ago is not current");
        assertTrue(snapshot.derivedQuoteAgeMs() > 7_000_000L);
    }

    @Test
    void aQuoteWithNoTimestampIsTreatedAsStaleNotAsCurrent() {
        MarketSnapshot snapshot = adapterFor("{\"c\":101.25}").getSnapshot("SPY");

        assertEquals(MarketDataStatus.STALE, snapshot.status());
    }

    @Test
    void anOldPolygonQuoteIsDemotedFromLive() {
        long twoMinutesAgo = System.currentTimeMillis() - 120_000L;
        LiveSpotProvider provider = new LiveSpotProvider(null, "pk", null, null,
                (url, headers) -> "{\"lastQuote\":{\"p\":123.45,\"P\":123.55,\"t\":" + twoMinutesAgo + "}}");

        assertEquals(MarketDataStatus.STALE, new LiveMarketSnapshotAdapter(provider, 0.01d).getSnapshot("SPY").status());
    }

    @Test
    void whenKeysExistButNothingAnswersTheSnapshotIsUnavailableWithUnknownAge() {
        LiveSpotProvider provider = new LiveSpotProvider("fh", null, null, null, (url, headers) -> { throw new java.io.IOException("down"); });

        MarketSnapshot snapshot = new LiveMarketSnapshotAdapter(provider, 100.0, 0.01d).getSnapshot("SPY");

        assertEquals(MarketDataStatus.UNAVAILABLE, snapshot.status());
        assertEquals(Instant.EPOCH, snapshot.sourceTimestamp(), "no source time exists, so none may be invented");
        assertTrue(snapshot.derivedQuoteAgeMs() > 86_400_000L, "unknown age must read as very old, not zero");
    }

    @Test
    void fallsBackToSimulatedSnapshotWhenFeedIsUnavailable() {
        LiveSpotProvider provider = new LiveSpotProvider(null, null, null, null, (url, headers) -> "{}");
        LiveMarketSnapshotAdapter adapter = new LiveMarketSnapshotAdapter(provider, 100.0, 0.01d);

        MarketSnapshot snapshot = adapter.getSnapshot("SPY");

        assertEquals("SPY", snapshot.symbol());
        assertEquals(MarketDataStatus.SIMULATED, snapshot.status());
        assertTrue(snapshot.bid() > 0.0);
        assertTrue(snapshot.ask() > snapshot.bid());
        assertNotNull(snapshot.timestamp());
    }
}
