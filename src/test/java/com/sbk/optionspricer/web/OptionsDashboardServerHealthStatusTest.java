package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionsDashboardServerHealthStatusTest {

    @Test
    void healthSnapshotReportsLiveSourceStateAndTimestamp() {
        LiveSpotProvider provider = new LiveSpotProvider(
                "demo-key",
                "demo-key",
                null,
                null,
                (url, headers) -> "{\"lastQuote\": {\"p\": 123.45, \"P\": 123.55, \"t\": " + System.currentTimeMillis() + "}}"
        );

        String json = OptionsDashboardServer.healthSnapshot(new com.sbk.optionspricer.market.LiveMarketSnapshotAdapter(provider));

        assertTrue(json.contains("\"sourceStatus\":\"LIVE\""));
        assertTrue(json.contains("\"lastUpdate\":"));
    }

    @Test
    void healthSnapshotFallsBackToSimulatedStateWithoutKeys() {
        LiveSpotProvider provider = new LiveSpotProvider(null, null, null, null, (url, headers) -> "{}");

        String json = OptionsDashboardServer.healthSnapshot(new com.sbk.optionspricer.market.LiveMarketSnapshotAdapter(provider));

        assertTrue(json.contains("\"sourceStatus\":\"SIMULATED\""));
        assertTrue(json.contains("\"lastUpdate\":"));
    }
}
