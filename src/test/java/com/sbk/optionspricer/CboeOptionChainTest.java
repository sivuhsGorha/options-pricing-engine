package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The Cboe feed is decoded faithfully, fetched once per symbol, and failures say what happened. */
class CboeOptionChainTest {

    private static String contract(String occ, double bid, double ask, double iv, double volume, double oi) {
        return String.format(java.util.Locale.ROOT,
                "{\"option\":\"%s\",\"bid\":%s,\"ask\":%s,\"iv\":%s,\"volume\":%s,\"open_interest\":%s,\"delta\":0.5}",
                occ, bid, ask, iv, volume, oi);
    }

    private static final String FIXTURE = "{\"timestamp\":\"2026-10-07 11:32:30\",\"symbol\":\"SPY\",\"data\":{\"symbol\":\"SPY\",\"current_price\":500.0,"
            + "\"options\":["
            + contract("SPY261120C00480000", 22.1, 22.4, 0.19, 120, 3400) + ","
            + contract("SPY261120P00480000", 2.05, 2.15, 0.21, 80, 9100) + ","
            + contract("SPY261120C00500000", 9.8, 10.0, 0.18, 500, 12000) + ","
            + contract("SPY261120P00500000", 9.5, 9.7, 0.18, 410, 8000) + ","
            + contract("SPY261120C00520000", 2.4, 2.5, 0.17, 60, 5000) + ","
            + contract("SPY261120P00520000", 22.0, 22.3, 0.2, 10, 700) + ","
            + contract("SPY261218P00490000", 7.1, 7.3, 0.2, 5, 100) + ","
            + contract("SPY261218C00510000", 8.9, 9.1, 0.19, 7, 200) + ","
            + "{\"option\":\"NOT-AN-OCC-SYMBOL\",\"bid\":1,\"ask\":2},"
            + contract("SPY261218C00530000", -1, 2.0, 0.19, 0, 0)
            + "]}}";

    private static final Instant T0 = Instant.parse("2026-10-07T15:40:00Z");

    private static final class Ticking extends Clock {
        Instant now = T0;

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void decodesOccSymbolsIntoExpiriesStrikesAndTypesAndSkipsWhatItCannotParse() {
        CboeOptionChain cboe = new CboeOptionChain(url -> FIXTURE, Clock.fixed(T0, ZoneOffset.UTC));

        assertEquals(List.of(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 18)), cboe.listExpiries("spy", LocalDate.of(2026, 10, 7)));
        assertEquals(List.of(LocalDate.of(2026, 12, 18)), cboe.listExpiries("SPY", LocalDate.of(2026, 11, 25)), "past expiries are not offered");

        OptionChain november = cboe.getOptionChain("SPY", LocalDate.of(2026, 11, 20));
        assertEquals(500.0, november.spot());
        assertEquals(6, november.quotes().size());
        OptionQuote call480 = november.calls().get(0);
        assertEquals(480.0, call480.strike());
        assertEquals(OptionType.CALL, call480.type());
        assertEquals(22.1, call480.bid());
        assertEquals(22.4, call480.ask());
        assertEquals(0.19, call480.impliedVolatility());
        assertEquals(120L, call480.volume());
        assertEquals(3400L, call480.openInterest());
        assertEquals(List.of(480.0, 500.0, 520.0), november.puts().stream().map(OptionQuote::strike).toList());

        OptionChain december = cboe.getOptionChain("SPY", LocalDate.of(2026, 12, 18));
        assertEquals(2, december.quotes().size(), "the malformed symbol and the negative bid are skipped");
    }

    @Test
    void theChainIsLabelledAsDelayedMarketDataWithTheFeedTimestamp() {
        CboeOptionChain cboe = new CboeOptionChain(url -> FIXTURE, Clock.fixed(T0, ZoneOffset.UTC));

        OptionChainProvider.SourcedChain sourced = cboe.getSourcedChain("SPY", LocalDate.of(2026, 11, 20));

        assertEquals("CBOE_DELAYED", sourced.source());
        assertTrue(sourced.marketData(), "delayed quotes are still market data, unlike the synthetic fallback");
        assertTrue(sourced.notes().get(0).contains("2026-10-07 11:32:30") && sourced.notes().get(0).contains("delayed"), sourced.notes().toString());
        assertEquals(Instant.parse("2026-10-07T11:32:30Z"), sourced.asOf(), "the feed timestamp is the document's generation time in UTC");
        assertNull(CboeOptionChain.parseFeedTimestamp("yesterday"));
        assertNull(CboeOptionChain.parseFeedTimestamp(null));
    }

    @Test
    void oneRequestServesTheExpiryListAndEveryChainUntilTheCacheExpires() {
        AtomicInteger requests = new AtomicInteger();
        Ticking clock = new Ticking();
        CboeOptionChain cboe = new CboeOptionChain(url -> { requests.incrementAndGet(); assertTrue(url.endsWith("/SPY.json"), url); return FIXTURE; }, clock);

        cboe.listExpiries("SPY", LocalDate.of(2026, 10, 7));
        cboe.getOptionChain("SPY", LocalDate.of(2026, 11, 20));
        cboe.getOptionChain("SPY", LocalDate.of(2026, 12, 18));
        assertEquals(1, requests.get(), "the document is fetched once and reused");

        clock.now = T0.plus(CboeOptionChain.CACHE_TTL).plus(Duration.ofSeconds(1));
        cboe.getOptionChain("SPY", LocalDate.of(2026, 11, 20));
        assertEquals(2, requests.get(), "after the TTL the document is refreshed");
    }

    @Test
    void failuresNameTheCauseInsteadOfReturningAnEmptyChain() {
        CboeOptionChain down = new CboeOptionChain(url -> { throw new IOException("Cboe returned HTTP 503"); }, Clock.fixed(T0, ZoneOffset.UTC));
        RuntimeException e = assertThrows(RuntimeException.class, () -> down.getOptionChain("SPY", LocalDate.of(2026, 11, 20)));
        assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());

        CboeOptionChain fixture = new CboeOptionChain(url -> FIXTURE, Clock.fixed(T0, ZoneOffset.UTC));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> fixture.getOptionChain("SPY", LocalDate.of(2027, 1, 15)));
        assertTrue(unknown.getMessage().contains("2027-01-15"), unknown.getMessage());

        assertThrows(RuntimeException.class, () -> new CboeOptionChain(url -> "not json", Clock.fixed(T0, ZoneOffset.UTC)).listExpiries("SPY", LocalDate.of(2026, 10, 7)));
        assertThrows(RuntimeException.class, () -> new CboeOptionChain(url -> "{\"data\":{\"current_price\":500,\"options\":[]}}", Clock.fixed(T0, ZoneOffset.UTC)).listExpiries("SPY", LocalDate.of(2026, 10, 7)));
        assertThrows(IllegalArgumentException.class, () -> fixture.getOptionChain("../etc", LocalDate.of(2026, 11, 20)), "the symbol goes into a URL and is validated");
    }
}
