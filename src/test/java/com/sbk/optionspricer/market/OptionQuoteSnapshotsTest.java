package com.sbk.optionspricer.market;

import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** An option quote becomes a snapshot the order gate can judge, with no invented book or freshness. */
class OptionQuoteSnapshotsTest {

    private static final Instant NOW = Instant.parse("2026-10-07T15:00:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 20);

    private static OptionQuote quote(double bid, double ask) {
        return new OptionQuote("SPY", EXPIRY, 780.0, OptionType.CALL, bid, ask, 0.0, 120L, 3400L);
    }

    @Test
    void aFreshTwoSidedMarketQuoteIsDelayedWithItsOwnBookAndContract() {
        MarketSnapshot s = OptionQuoteSnapshots.toSnapshot(quote(9.8, 10.0), "SPY", 100, "CBOE_DELAYED", true, NOW.minusSeconds(40), NOW).orElseThrow();

        assertEquals("SPY261120C00780000", s.symbol());
        assertTrue(s.isOption());
        assertEquals("SPY", s.underlying());
        assertEquals(100, s.multiplierOr(1));
        assertEquals(9.8, s.bid());
        assertEquals(10.0, s.ask());
        assertEquals(9.9, s.last(), 1e-12);
        assertEquals(120L, s.volume());
        assertEquals(MarketDataStatus.DELAYED, s.status());
        assertEquals(40_000L, s.derivedQuoteAgeMs());
        assertEquals("CBOE_DELAYED", s.source());
    }

    @Test
    void aQuoteOlderThanTheWindowOrWithoutAFeedTimeIsStale() {
        assertEquals(MarketDataStatus.STALE, OptionQuoteSnapshots.toSnapshot(quote(9.8, 10.0), "SPY", 100, "CBOE_DELAYED", true, NOW.minusSeconds(200), NOW).orElseThrow().status());
        MarketSnapshot unknownAge = OptionQuoteSnapshots.toSnapshot(quote(9.8, 10.0), "SPY", 100, "CBOE_DELAYED", true, null, NOW).orElseThrow();
        assertEquals(MarketDataStatus.STALE, unknownAge.status());
        assertTrue(unknownAge.derivedQuoteAgeMs() > 86_400_000L, "unknown age reads as very old, never as fresh");
    }

    @Test
    void aOneSidedMarketIsUnavailableNotAPrice() {
        MarketSnapshot noBid = OptionQuoteSnapshots.toSnapshot(quote(0.0, 0.05), "SPY", 100, "CBOE_DELAYED", true, NOW, NOW).orElseThrow();
        assertEquals(MarketDataStatus.UNAVAILABLE, noBid.status());
        assertFalse(noBid.hasBook());
        assertEquals(0.05, noBid.last(), "the only price there is, kept for display, but never tradable");

        MarketSnapshot crossed = OptionQuoteSnapshots.toSnapshot(quote(10.0, 9.8), "SPY", 100, "CBOE_DELAYED", true, NOW, NOW).orElseThrow();
        assertEquals(MarketDataStatus.UNAVAILABLE, crossed.status());

        assertEquals(Optional.empty(), OptionQuoteSnapshots.toSnapshot(quote(0.0, 0.0), "SPY", 100, "CBOE_DELAYED", true, NOW, NOW), "no price at all: no snapshot");
    }

    @Test
    void aGeneratedChainYieldsSimulatedQuotesWhateverTheirAge() {
        MarketSnapshot s = OptionQuoteSnapshots.toSnapshot(quote(9.8, 10.0), "SPY", 100, "SYNTHETIC", false, NOW.minusSeconds(100_000), NOW).orElseThrow();

        assertEquals(MarketDataStatus.SIMULATED, s.status(), "never presented as delayed market data");
        assertEquals("SYNTHETIC", s.source());
    }
}
