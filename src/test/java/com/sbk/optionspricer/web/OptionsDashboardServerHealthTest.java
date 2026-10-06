package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionsDashboardServerHealthTest {

    @Test
    void serializesMarketFeedHealthForDashboardConsumers() {
        LiveSpotProvider provider = new LiveSpotProvider(
                "demo-finnhub",
                "demo-polygon",
                null,
                "demo-marketstack",
                (url, headers) -> {
                    if (url.contains("finnhub")) {
                        return "{\"c\":101.25,\"t\":" + System.currentTimeMillis() / 1000 + "}"; // a current quote
                    }
                    if (url.contains("polygon")) {
                        return "{\"results\":[{\"c\":101.75}]}";
                    }
                    if (url.contains("marketstack")) {
                        return "{\"data\":[{\"close\":102.40}]}";
                    }
                    return "{}";
                }
        );

        String json = OptionsDashboardServer.healthSnapshot(new com.sbk.optionspricer.market.LiveMarketSnapshotAdapter(provider));

        assertTrue(json.contains("LIVE") || json.contains("DELAYED"));
    }
}
