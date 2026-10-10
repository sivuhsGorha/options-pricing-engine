package com.sbk.optionspricer.core;

import com.sbk.optionspricer.CompositeOptionChainProvider;
import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.volatility.VolatilitySurfaceSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Calibration runs off the startup path, reports its status, and labels where the chains came from. */
class VolatilitySurfaceServiceTest {

    private static final OptionChainProvider FAILING = new OptionChainProvider() {
        @Override
        public OptionChain getOptionChain(String symbol, LocalDate expiry) {
            throw new RuntimeException("boom: venue down");
        }

        @Override
        public String sourceName() {
            return "FAKE_VENUE";
        }
    };

    /** Looks like a market provider (so the label must say so) but serves generated chains. */
    private static final OptionChainProvider FAKE_VENUE = new OptionChainProvider() {
        private final SyntheticOptionChainProvider inner = new SyntheticOptionChainProvider(100.0, 0.2, 0.05, 0.0);

        @Override
        public OptionChain getOptionChain(String symbol, LocalDate expiry) {
            return inner.getOptionChain(symbol, expiry);
        }

        @Override
        public String sourceName() {
            return "FAKE_VENUE";
        }
    };

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        java.time.Instant now = java.time.Instant.parse("2026-10-09T13:30:00Z");
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    /** A venue serving generated chains as market data, each stamped with the time it was served; counts what it served and can go down. */
    static final class StampingVenue implements OptionChainProvider {
        private final SyntheticOptionChainProvider inner = new SyntheticOptionChainProvider(100.0, 0.2, 0.05, 0.0);
        private final Clock clock;
        final AtomicInteger served = new AtomicInteger();
        volatile boolean down;

        StampingVenue(Clock clock) {
            this.clock = clock;
        }

        @Override
        public OptionChain getOptionChain(String symbol, LocalDate expiry) {
            if (down) throw new RuntimeException("Cboe returned HTTP 503");
            served.incrementAndGet();
            return inner.getOptionChain(symbol, expiry);
        }

        @Override
        public OptionChainProvider.SourcedChain getSourcedChain(String symbol, LocalDate expiry) {
            return new OptionChainProvider.SourcedChain(getOptionChain(symbol, expiry), "FAKE_VENUE", true, List.of(), clock.instant());
        }

        @Override
        public String sourceName() {
            return "FAKE_VENUE";
        }
    }

    private static VolatilitySurfaceService service(OptionChainProvider provider, AtomicInteger chainsSeen) {
        return new VolatilitySurfaceService(provider, "SPY", 0.05, 0.0, Duration.ofMinutes(15), Clock.systemUTC(),
                chain -> { if (chainsSeen != null) chainsSeen.incrementAndGet(); });
    }

    @Test
    void beforeTheFirstRunTheStatusIsLoadingAndThereIsNoSurface() {
        VolatilitySurfaceService service = service(new SyntheticOptionChainProvider(), null);

        assertEquals(VolatilitySurfaceSource.State.LOADING, service.status().state());
        assertTrue(service.latest().isEmpty());
    }

    @Test
    void aProviderThatFailsLeavesAFailedStatusWithTheReasonAndNoSurface() {
        VolatilitySurfaceService service = service(FAILING, null);

        service.refresh();

        assertEquals(VolatilitySurfaceSource.State.FAILED, service.status().state());
        assertTrue(service.status().message().contains("boom"), service.status().message());
        assertTrue(service.latest().isEmpty());
    }

    @Test
    void aSyntheticProviderIsReadyButLabelledAsNotMarketData() {
        AtomicInteger chains = new AtomicInteger();
        VolatilitySurfaceService service = service(new SyntheticOptionChainProvider(100.0, 0.2, 0.05, 0.0), chains);

        service.refresh();

        assertEquals(VolatilitySurfaceSource.State.READY, service.status().state(), service.status().message());
        VolatilitySurfaceSource.Snapshot snapshot = service.latest().orElseThrow();
        assertEquals("SYNTHETIC", snapshot.source());
        assertFalse(snapshot.marketData(), "generated prices must never be presented as a market-fitted surface");
        assertNotNull(snapshot.ssvi());
        assertNotNull(snapshot.sabr());
        assertNotNull(snapshot.svi());
        assertEquals(100.0, snapshot.spot());
        assertEquals(4, chains.get(), "each loaded chain is handed to the history callback");
    }

    @Test
    void theLoadedChainsAreOptionMarketDataWithTheirProvenance() {
        VolatilitySurfaceService service = service(new SyntheticOptionChainProvider(100.0, 0.2, 0.05, 0.0), null);
        assertTrue(service.loadedExpiries().isEmpty(), "nothing before the first load");
        assertTrue(service.optionQuote("SPY261120C00100000").isEmpty());

        service.refresh();

        List<LocalDate> expiries = service.loadedExpiries();
        assertEquals(4, expiries.size());
        LocalDate first = expiries.get(0);
        assertTrue(service.chain(first).isPresent());
        String contract = com.sbk.optionspricer.instruments.OccSymbol.format("SPY", first, com.sbk.optionspricer.OptionType.CALL, 100.0);

        com.sbk.optionspricer.market.MarketSnapshot quote = service.optionQuote(contract).orElseThrow();
        assertEquals(contract, quote.symbol());
        assertEquals(100, quote.multiplierOr(1));
        assertTrue(quote.hasBook(), "the synthetic chain quotes a two-sided market");
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.SIMULATED, quote.status(), "a generated chain can never pass as market data");
        assertEquals("SYNTHETIC", quote.source());

        assertTrue(service.optionQuote("SPY261120C00123456").isEmpty(), "a strike that is not in the chain");
        assertTrue(service.optionQuote("QQQ" + contract.substring(3)).isEmpty(), "another underlying");
        assertTrue(service.optionQuote("not a contract").isEmpty());
    }

    @Test
    void theLabelNamesTheProviderThatActuallyAnswered() {
        VolatilitySurfaceService fallback = service(new CompositeOptionChainProvider(List.of(FAILING, new SyntheticOptionChainProvider())), null);
        fallback.refresh();
        assertEquals("SYNTHETIC", fallback.latest().orElseThrow().source());
        assertFalse(fallback.latest().orElseThrow().marketData());
        assertTrue(fallback.latest().orElseThrow().warnings().stream().anyMatch(w -> w.contains("FAKE_VENUE") && w.contains("boom")),
                "the fallback must say why the market provider was not used: " + fallback.latest().orElseThrow().warnings());

        VolatilitySurfaceService venue = service(new CompositeOptionChainProvider(List.of(FAKE_VENUE, new SyntheticOptionChainProvider())), null);
        venue.refresh();
        assertEquals("FAKE_VENUE", venue.latest().orElseThrow().source());
        assertTrue(venue.latest().orElseThrow().marketData());
    }

    @Test
    void expiriesAreTheListedDatesNearestToEachTargetTenorWithoutDuplicates() {
        LocalDate today = LocalDate.of(2026, 10, 7);
        List<LocalDate> weekly = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 10, 9); !d.isAfter(LocalDate.of(2026, 12, 25)); d = d.plusWeeks(1)) weekly.add(d);
        weekly.add(LocalDate.of(2027, 3, 19));

        assertEquals(List.of(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 12, 4), LocalDate.of(2026, 12, 25), LocalDate.of(2027, 3, 19)),
                VolatilitySurfaceService.selectExpiries(weekly, today));
        assertEquals(List.of(LocalDate.of(2026, 11, 6)), VolatilitySurfaceService.selectExpiries(List.of(LocalDate.of(2026, 11, 6)), today));
        assertTrue(VolatilitySurfaceService.selectExpiries(List.of(LocalDate.of(2026, 10, 9)), today).isEmpty(), "two days out is too close to expiry");
    }

    @Test
    void theDefaultExpiriesAreTheNextMonthlyThirdFridays() {
        assertEquals(List.of(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 18), LocalDate.of(2027, 1, 15)),
                OptionChainProvider.thirdFridays(LocalDate.of(2026, 10, 7), 4));
        // Less than a week away: skip to the next month.
        assertEquals(LocalDate.of(2026, 11, 20), OptionChainProvider.thirdFridays(LocalDate.of(2026, 10, 12), 1).get(0));
    }

    @Test
    void aSyntheticFallbackForOneExpiryIsDroppedRatherThanMixedWithMarketChains() {
        // A venue that serves three expiries at spot 100 but fails on the second one.
        OptionChainProvider flaky = new OptionChainProvider() {
            private final SyntheticOptionChainProvider inner = new SyntheticOptionChainProvider(100.0, 0.2, 0.05, 0.0);
            private int calls;

            @Override
            public OptionChain getOptionChain(String symbol, LocalDate expiry) {
                if (++calls == 2) throw new RuntimeException("Cboe returned HTTP 502");
                return inner.getOptionChain(symbol, expiry);
            }

            @Override
            public String sourceName() {
                return "FAKE_VENUE";
            }
        };
        VolatilitySurfaceService service = service(new CompositeOptionChainProvider(List.of(flaky, new SyntheticOptionChainProvider(55.0, 0.2, 0.05, 0.0))), null);

        service.refresh();

        VolatilitySurfaceSource.Snapshot s = service.latest().orElseThrow();
        assertEquals("FAKE_VENUE", s.source(), "the synthetic chain is not part of the surface's provenance");
        assertTrue(s.marketData());
        assertEquals(100.0, s.spot(), "the spot is the venue's, never the fallback's 55");
        assertEquals(3, service.loadedExpiries().size(), "the failed expiry is absent, not replaced by a generated one");
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("fallback dropped")), s.warnings().toString());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("HTTP 502")), "and the venue failure itself is still reported");
    }

    @Test
    void theWorkerRetriesSoonWhileItHasNoMarketDataSurface() {
        VolatilitySurfaceService failing = service(FAILING, null);
        assertEquals(VolatilitySurfaceService.RETRY_WHEN_DEGRADED, failing.nextDelay(), "nothing fitted yet: retry soon");
        failing.refresh();
        assertEquals(VolatilitySurfaceService.RETRY_WHEN_DEGRADED, failing.nextDelay(), "failed: retry soon");

        VolatilitySurfaceService synthetic = service(new SyntheticOptionChainProvider(), null);
        synthetic.refresh();
        assertEquals(VolatilitySurfaceService.RETRY_WHEN_DEGRADED, synthetic.nextDelay(), "a DEMO surface is a fallback: keep trying for market data");

        VolatilitySurfaceService market = service(new CompositeOptionChainProvider(List.of(FAKE_VENUE, new SyntheticOptionChainProvider())), null);
        market.refresh();
        assertEquals(VolatilitySurfaceService.DEFAULT_QUOTE_REFRESH, market.nextDelay(), "a market-data surface ticks every quote interval; the refit waits the full interval");

        VolatilitySurfaceService slowQuotes = new VolatilitySurfaceService(new CompositeOptionChainProvider(List.of(FAKE_VENUE, new SyntheticOptionChainProvider())),
                "SPY", 0.05, 0.0, Duration.ofMinutes(15), Duration.ofMinutes(5), Clock.systemUTC(), null);
        slowQuotes.refresh();
        assertEquals(Duration.ofMinutes(5), slowQuotes.nextDelay());
        VolatilitySurfaceService quotesSlowerThanFits = new VolatilitySurfaceService(FAKE_VENUE, "SPY", 0.05, 0.0, Duration.ofMinutes(2), Duration.ofMinutes(5), Clock.systemUTC(), null);
        quotesSlowerThanFits.refresh();
        assertEquals(Duration.ofMinutes(2), quotesSlowerThanFits.nextDelay(), "the quote interval never exceeds the calibration interval");
    }

    // Live on 2026-10-09: the chains were loaded only at each 15-minute calibration and a quote is STALE 120 s after
    // the feed stamped it, so the vol-spread strategy saw tradable quotes for about two minutes in fifteen and
    // spent the morning on "WAIT - quote STALE". Quotes now reload on their own cadence; the surface refits on its own.

    @Test
    void quotesAreReloadedOnTheirOwnCadenceAndTheSurfaceRefitsOnlyOnTheLongOne() {
        ManualClock clock = new ManualClock();
        StampingVenue venue = new StampingVenue(clock);
        VolatilitySurfaceService service = new VolatilitySurfaceService(venue, "SPY", 0.05, 0.0, Duration.ofMinutes(15), Duration.ofMinutes(1), clock, null);
        java.time.Instant t0 = clock.now;
        assertTrue(service.fitDue(clock.now), "nothing fitted yet");
        assertTrue(service.lastQuoteRefreshAt().isEmpty());

        service.runOnce();

        assertEquals(VolatilitySurfaceSource.State.READY, service.status().state(), service.status().message());
        assertEquals(t0, service.latest().orElseThrow().asOf());
        assertEquals(4, venue.served.get(), "the calibration loaded four chains");
        assertEquals(t0, service.lastQuoteRefreshAt().orElseThrow());
        String contract = com.sbk.optionspricer.instruments.OccSymbol.format("SPY", service.loadedExpiries().get(0), com.sbk.optionspricer.OptionType.CALL, 100.0);
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.DELAYED, service.optionQuote(contract).orElseThrow().status());

        clock.now = t0.plusSeconds(150);
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.STALE, service.optionQuote(contract).orElseThrow().status(),
                "the chains loaded at the calibration have aged past the 120 s window");
        assertFalse(service.fitDue(clock.now), "a market-data surface is refitted only every 15 minutes");
        assertEquals(Duration.ofMinutes(1), service.nextDelay(), "but the worker ticks every quote interval");

        service.runOnce();

        assertEquals(8, venue.served.get(), "the tick reloaded the chains");
        assertEquals(t0, service.latest().orElseThrow().asOf(), "and did not refit");
        assertEquals(clock.now, service.lastQuoteRefreshAt().orElseThrow());
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.DELAYED, service.optionQuote(contract).orElseThrow().status(), "the quotes are fresh again");

        clock.now = t0.plus(Duration.ofMinutes(15));
        assertTrue(service.fitDue(clock.now));
        service.runOnce();
        assertEquals(clock.now, service.latest().orElseThrow().asOf(), "refitted on the long cadence");
        assertEquals(12, venue.served.get());
    }

    // Found on review (2026-10-10): the chain-loaded callback stores quotes in the historical snapshot file, and the
    // per-minute reload added in 6ceee6d fired it too, appending sixteen rows a minute to a file nothing reads.

    @Test
    void aQuoteReloadDoesNotFeedTheChainHistoryOnlyACalibrationDoes() {
        ManualClock clock = new ManualClock();
        StampingVenue venue = new StampingVenue(clock);
        AtomicInteger recorded = new AtomicInteger();
        VolatilitySurfaceService service = new VolatilitySurfaceService(venue, "SPY", 0.05, 0.0, Duration.ofMinutes(15), Duration.ofMinutes(1),
                clock, chain -> recorded.incrementAndGet());
        java.time.Instant t0 = clock.now;

        service.runOnce();
        assertEquals(4, recorded.get(), "the calibration recorded its four chains");

        clock.now = t0.plusSeconds(60);
        service.runOnce();
        assertEquals(8, venue.served.get(), "the minute tick reloaded the chains");
        assertEquals(4, recorded.get(), "but recorded nothing");

        clock.now = t0.plus(Duration.ofMinutes(15));
        service.runOnce();
        assertEquals(8, recorded.get(), "the next calibration records again");
    }

    @Test
    void aFailedQuoteReloadKeepsTheLastChainsAndTheSurface() {
        ManualClock clock = new ManualClock();
        StampingVenue venue = new StampingVenue(clock);
        VolatilitySurfaceService service = new VolatilitySurfaceService(venue, "SPY", 0.05, 0.0, Duration.ofMinutes(15), Duration.ofMinutes(1), clock, null);
        java.time.Instant t0 = clock.now;
        service.runOnce();
        assertEquals(4, service.loadedExpiries().size());

        venue.down = true;
        clock.now = t0.plusSeconds(60);
        assertFalse(service.refreshQuotes(), "nothing loaded");

        assertEquals(VolatilitySurfaceSource.State.READY, service.status().state(), "a failed reload does not fail the surface");
        assertEquals(t0, service.latest().orElseThrow().asOf());
        assertEquals(4, service.loadedExpiries().size(), "the last chains are kept");
        assertEquals(t0, service.lastQuoteRefreshAt().orElseThrow(), "and the reload time is not advanced");
        String contract = com.sbk.optionspricer.instruments.OccSymbol.format("SPY", service.loadedExpiries().get(0), com.sbk.optionspricer.OptionType.CALL, 100.0);
        clock.now = t0.plusSeconds(200);
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.STALE, service.optionQuote(contract).orElseThrow().status(), "so the old quotes age into STALE and the strategy waits");

        venue.down = false;
        service.runOnce();
        assertEquals(VolatilitySurfaceSource.State.READY, service.status().state());
        assertEquals(com.sbk.optionspricer.market.MarketDataStatus.DELAYED, service.optionQuote(contract).orElseThrow().status());
    }

    @Test
    void theWorkerCalibratesInTheBackgroundAndStops() throws Exception {
        VolatilitySurfaceService service = service(new SyntheticOptionChainProvider(), null);
        service.start();
        long deadline = System.currentTimeMillis() + 15_000;
        while (service.status().state() == VolatilitySurfaceSource.State.LOADING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(VolatilitySurfaceSource.State.READY, service.status().state(), service.status().message());
        service.close();
    }
}
