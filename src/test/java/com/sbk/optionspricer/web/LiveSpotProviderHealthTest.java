package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveSpotProviderHealthTest {

    @Test
    void reportsLiveDelayedAndUnavailableStatusesPerMarketDataProvider() {
        LiveSpotProvider provider = new LiveSpotProvider(
                "demo-finnhub",
                "demo-polygon",
                null,
                "demo-marketstack",
                (url, headers) -> {
                    if (url.contains("finnhub")) {
                        return "{\"c\":101.25}";
                    }
                    if (url.contains("polygon")) {
                        return "{\"lastQuote\": {\"p\": 101.70, \"P\": 101.80, \"t\": " + System.currentTimeMillis() + "}}";
                    }
                    if (url.contains("marketstack")) {
                        return "{\"data\":[{\"close\":102.40}]}";
                    }
                    return "{}";
                }
        );

        Map<String, String> health = provider.getFeedStatus("SPY");

        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.DELAYED.name(), health.get("FINNHUB"));
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.LIVE.name(), health.get("POLYGON"));
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.DELAYED.name(), health.get("MARKETSTACK"));
        assertFalse(health.isEmpty());
    }

    @Test
    void doesNotReturnSyntheticDataWhenNoMarketKeysAreConfigured() {
        LiveSpotProvider provider = new LiveSpotProvider(null, null, null, null, (url, headers) -> "{}");

        LiveSpotProvider.Quote quote = provider.getQuote("SPY");

        assertNotNull(quote);
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.UNAVAILABLE, quote.status());
        assertTrue(Double.isNaN(quote.last()));
    }
}
