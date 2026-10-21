package com.sbk.optionspricer.market;

import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.web.LiveSpotProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** How old a price may be before it is STALE depends on where it came from: a streaming quote ages in seconds, an end-of-day feed in minutes. */
class StalenessPolicyTest {

    @AfterEach
    void restoreDefaults() {
        StalenessPolicy.configure(StalenessPolicy.defaults());
    }

    @Test
    void withoutOverridesTheLimitsAreThoseOfTheStatus() {
        StalenessPolicy defaults = StalenessPolicy.defaults();

        assertEquals(30_000L, defaults.limitMs("POLYGON", MarketDataStatus.LIVE));
        assertEquals(120_000L, defaults.limitMs("FINNHUB", MarketDataStatus.DELAYED));
    }

    @Test
    void aSourceOverrideWinsAndNamesAreNotCaseSensitive() {
        StalenessPolicy policy = StalenessPolicy.fromSpec("polygon=10, FINNHUB=300");

        assertEquals(10_000L, policy.limitMs("POLYGON", MarketDataStatus.LIVE));
        assertEquals(300_000L, policy.limitMs("finnhub", MarketDataStatus.DELAYED));
        assertEquals(120_000L, policy.limitMs("ALPHA_VANTAGE", MarketDataStatus.DELAYED), "a source without an override keeps the default");
    }

    @Test
    void aMalformedOrSillyLimitIsRefusedAtStartInsteadOfIgnored() {
        for (String bad : new String[] {"POLYGON", "POLYGON=abc", "POLYGON=0", "POLYGON=-5", "POLYGON=100000", "=10"}) {
            assertThrows(IllegalArgumentException.class, () -> StalenessPolicy.fromSpec(bad), bad);
        }
        assertEquals(StalenessPolicy.defaults().limitMs("X", MarketDataStatus.LIVE), StalenessPolicy.fromSpec("  ").limitMs("X", MarketDataStatus.LIVE),
                "an empty setting is no override");
    }

    @Test
    void theSpotAdapterJudgesEachProviderByItsOwnLimit() {
        StalenessPolicy.configure(StalenessPolicy.fromSpec("POLYGON=5,FINNHUB=600"));
        long now = System.currentTimeMillis();

        LiveSpotProvider polygon = new LiveSpotProvider("demo-key", "demo-key", null, null,
                (url, headers) -> "{\"lastQuote\": {\"p\": 99.9, \"P\": 100.1, \"t\": " + (now - 10_000L) + "}}");
        LiveSpotProvider finnhub = new LiveSpotProvider("fh", null, null, null,
                (url, headers) -> "{\"c\":100.0,\"t\":" + ((now - 300_000L) / 1000) + "}");

        assertEquals(MarketDataStatus.STALE, new LiveMarketSnapshotAdapter(polygon, 0.01d).getSnapshot("SPY").status(),
                "10 s is past this provider's own 5 s limit (the default 30 s would have passed it)");
        assertEquals(MarketDataStatus.DELAYED, new LiveMarketSnapshotAdapter(finnhub, 0.01d).getSnapshot("SPY").status(),
                "5 min is inside this provider's 10 min limit (the default 2 min would have failed it)");
    }

    @Test
    void optionQuotesFollowTheirSourceLimitToo() {
        OptionQuote quote = new OptionQuote("SPY", java.time.LocalDate.of(2026, 12, 18), 780.0, com.sbk.optionspricer.OptionType.CALL, 9.8, 10.0, 0.0, 120L, 3400L);
        Instant now = Instant.parse("2026-10-21T15:00:00Z");

        StalenessPolicy.configure(StalenessPolicy.fromSpec("ALPACA=20"));
        assertEquals(MarketDataStatus.STALE,
                OptionQuoteSnapshots.toSnapshot(quote, "SPY", 100, "ALPACA", true, now.minusSeconds(40), now).orElseThrow().status());
        assertEquals(MarketDataStatus.DELAYED,
                OptionQuoteSnapshots.toSnapshot(quote, "SPY", 100, "CBOE_DELAYED", true, now.minusSeconds(40), now).orElseThrow().status(),
                "another source keeps the default 120 s");
    }
}
