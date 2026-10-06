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
        assertTrue(snapshot.volume() > 0L);
        assertNotNull(snapshot.timestamp());
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
