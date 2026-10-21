package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.market.OptionMarketData;
import com.sbk.optionspricer.market.OptionQuoteSnapshots;
import com.sbk.optionspricer.risk.FillRecorder;
import com.sbk.optionspricer.volatility.SurfaceFitter;
import com.sbk.optionspricer.volatility.VolatilitySurfaceSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The straddle strategy opens against a real edge, holds inside the band, closes on reversion or near expiry,
 * hedges delta with shares, and never legs into a position the gates refuse.
 */
class VolSpreadStrategyTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final double R = 0.05, Q = 0.0;

    /** Chains generated at one flat volatility, served as SIMULATED quotes (they are generated). */
    private static final class FlatChains implements OptionMarketData {
        private final SyntheticOptionChainProvider provider;
        private final List<LocalDate> expiries;

        FlatChains(double vol, LocalDate today) {
            this.provider = new SyntheticOptionChainProvider(100.0, vol, R, Q, Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
            this.expiries = OptionChainProvider.thirdFridays(today, 3);
        }

        @Override public List<LocalDate> loadedExpiries() { return expiries; }
        @Override public Optional<OptionChain> chain(LocalDate expiry) { return Optional.of(provider.getOptionChain("SPY", expiry)); }
        @Override public Optional<MarketSnapshot> optionQuote(String contractSymbol) {
            var parsed = OccSymbol.parse(contractSymbol).orElseThrow();
            OptionChain chain = provider.getOptionChain("SPY", parsed.expiry());
            return chain.quotes().stream().filter(q -> q.type() == parsed.type() && Math.abs(q.strike() - parsed.strike()) < 1e-9).findFirst()
                    .flatMap(q -> OptionQuoteSnapshots.toSnapshot(q, "SPY", 100, "SYNTHETIC", false, NOW, NOW));
        }
    }

    /** A reference surface fitted to chains at one flat volatility. */
    private static VolatilitySurfaceSource referenceSurface(double vol) {
        FlatChains source = new FlatChains(vol, TODAY);
        List<OptionChain> chains = new ArrayList<>();
        for (LocalDate e : source.loadedExpiries()) chains.add(source.chain(e).orElseThrow());
        SurfaceFitter.Fit ssvi = SurfaceFitter.fitSsvi(SurfaceFitter.extractPoints(chains, R, Q, TODAY).points());
        var snapshot = new VolatilitySurfaceSource.Snapshot(NOW, "SPY", "SYNTHETIC", false, 100.0, ssvi, null, null, 0, List.of());
        return new VolatilitySurfaceSource() {
            @Override public Optional<Snapshot> latest() { return Optional.of(snapshot); }
            @Override public Status status() { return new Status(State.READY, "ok", NOW); }
        };
    }

    private static ValuationSource valuationWithDelta(double netDelta) {
        return new ValuationSource() {
            @Override public Optional<Valuation> latestValuation() {
                return Optional.of(new Valuation(NOW, 100.0, "TEST/LIVE", List.of(), 0, 0, netDelta, 0, 0, 0, 0, List.of()));
            }
            @Override public String valuationStatus() { return ""; }
        };
    }

    private static MarketSnapshot spot() {
        return new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, NOW, NOW, 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static OrderManager manager(PositionTracker tracker) {
        return new OrderManager(new PreTradeRiskFilter(1_000, 1e9, 1_000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.allowSimulated(), new TradingHalt(), null, 1);
    }

    private static VolSpreadStrategy.Params params() {
        return new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI");
    }

    private static VolSpreadStrategy strategy(PositionTracker tracker, OptionMarketData market, double referenceVol, ValuationSource valuation, Clock clock) {
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), market, referenceSurface(referenceVol), valuation, VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, params(), clock);
        s.allowSimulatedChains(true); // these tests drive the strategy with generated chains on purpose
        return s;
    }

    @Test
    void refusesAGeneratedChainAndOneWhoseSpotDisagreesWithTheLiveSpot() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy live = new VolSpreadStrategy(manager(tracker), new FlatChains(0.23, TODAY), referenceSurface(0.20), null,
                VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));

        VolSpreadStrategy.Decision d = live.step();

        assertEquals("WAIT", d.action(), d.summary());
        assertTrue(d.reason().contains("SIMULATED"), d.reason());
        assertTrue(tracker.getPositions().isEmpty());

        // a chain built around 100 while the live spot is 777 (the synthetic fallback seen in production)
        MarketSnapshot farSpot = new MarketSnapshot("SPY", 776.99, 777.01, 777.0, 5000L, NOW, NOW, 0L, "TEST", MarketDataStatus.LIVE);
        VolSpreadStrategy mismatched = new VolSpreadStrategy(manager(tracker), new FlatChains(0.23, TODAY), referenceSurface(0.20), null,
                () -> farSpot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        mismatched.allowSimulatedChains(true);
        VolSpreadStrategy.Decision m = mismatched.step();
        assertEquals("WAIT", m.action(), m.summary());
        assertTrue(m.reason().contains("disagrees with the live spot"), m.reason());
    }

    @Test
    void doesNothingWhileTheSpotIsStaleEvenWithAHugeEdgeAndAnUnhedgedDelta() {
        // Overnight the option chain still reads as freshly loaded while the spot last printed at the previous close.
        // Observed live on 2026-10-13: five orders sent before the open on a spot 16 hours old.
        MarketSnapshot staleSpot = new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, NOW, NOW, 0L, "TEST", MarketDataStatus.STALE);
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new FlatChains(0.23, TODAY), referenceSurface(0.20), valuationWithDelta(500.0),
                () -> staleSpot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = s.step();

        assertEquals("WAIT", d.action(), d.summary());
        assertTrue(d.reason().contains("spot quote STALE"), d.reason());
        assertTrue(d.orders().isEmpty(), "no hedge and no straddle leg may be sent: " + d.orders());
        assertTrue(tracker.getPositions().isEmpty());

        // the same strategy trades again once the spot is fresh
        VolSpreadStrategy fresh = strategy(tracker, new FlatChains(0.23, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("SELL_STRADDLE", fresh.step().action());
    }

    @Test
    void aStaleOptionQuoteSaysHowOldTheFeedWasSoALogCanTellLagFromAFailedReload() {
        // 2026-10-13: a third of the steps with a fresh spot read "quote STALE", flipping with every 30 s step.
        // Whether the feed's own stamp lags the fetch or the reload fails needs the age in the log.
        FlatChains base = new FlatChains(0.23, TODAY);
        OptionMarketData aged = new OptionMarketData() {
            @Override public List<LocalDate> loadedExpiries() { return base.loadedExpiries(); }
            @Override public Optional<OptionChain> chain(LocalDate expiry) { return base.chain(expiry); }
            @Override public Optional<MarketSnapshot> optionQuote(String contractSymbol) {
                return base.optionQuote(contractSymbol).map(q -> new MarketSnapshot(q.symbol(), q.bid(), q.ask(), q.last(), q.volume(),
                        q.timestamp(), q.sourceTimestamp(), 150_000L, q.source(), MarketDataStatus.STALE, q.instrument()));
            }
        };
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, aged, 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));

        VolSpreadStrategy.Decision d = s.step();

        assertEquals("WAIT", d.action(), d.summary());
        assertTrue(d.reason().contains("quote STALE (feed 150 s old, limit 120 s)"), d.reason());
        assertTrue(tracker.getPositions().isEmpty());
    }

    /** The strategy's front month: the first loaded expiry at least min_days_to_expiry (14) out. */
    private static LocalDate front() {
        return OptionChainProvider.thirdFridays(TODAY, 3).stream().filter(e -> !e.isBefore(TODAY.plusDays(14))).findFirst().orElseThrow();
    }

    @Test
    void sellsTheAtmStraddleWhenTheMarketIsRichToTheReferenceAndClosesWhenTheEdgeReverts() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        // market quotes at 23% vol, reference surface at 20%: three points of edge
        VolSpreadStrategy rich = strategy(tracker, new FlatChains(0.23, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));

        VolSpreadStrategy.Decision open = rich.step();

        assertEquals("SELL_STRADDLE", open.action(), open.summary());
        assertEquals(front(), open.expiry());
        assertEquals(100.0, open.strike(), "the strike nearest the forward");
        assertEquals(0.23, open.marketIv(), 0.003);
        assertEquals(0.20, open.referenceIv(), 0.003);
        assertEquals(-1, open.straddleSign());
        String call = OccSymbol.format("SPY", front(), OptionType.CALL, 100.0);
        String put = OccSymbol.format("SPY", front(), OptionType.PUT, 100.0);
        assertEquals(-1, tracker.getNetQuantity(call));
        assertEquals(-1, tracker.getNetQuantity(put));
        assertEquals("HOLD", rich.step().action(), "still rich: hold");

        // the same book now sees quotes at 20%: the edge is gone
        VolSpreadStrategy reverted = new VolSpreadStrategy(manager(tracker), new FlatChains(0.20, TODAY), referenceSurface(0.20), null,
                VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        reverted.allowSimulatedChains(true);
        // adopt the open straddle: a fresh strategy object has no memory, so open one through it first
        assertEquals("HOLD", reverted.step().action(), "flat book, no edge: nothing to do");

        VolSpreadStrategy.Decision close = rich.stepWith(new FlatChains(0.20, TODAY));
        assertEquals("CLOSE", close.action(), close.summary());
        assertEquals(0, tracker.getNetQuantity(call));
        assertEquals(0, tracker.getNetQuantity(put));
    }

    @Test
    void buysWhenTheMarketIsCheapAndHoldsInsideTheBand() {
        PositionTracker cheapBook = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy cheap = strategy(cheapBook, new FlatChains(0.17, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("BUY_STRADDLE", cheap.step().action());
        assertEquals(1, cheapBook.getNetQuantity(OccSymbol.format("SPY", front(), OptionType.PUT, 100.0)));

        PositionTracker flatBook = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy inside = strategy(flatBook, new FlatChains(0.205, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));
        VolSpreadStrategy.Decision hold = inside.step();
        assertEquals("HOLD", hold.action());
        assertTrue(hold.orders().isEmpty());
        assertTrue(flatBook.getPositions().isEmpty());
    }

    @Test
    void closesAnOpenStraddleWhenItsExpiryComesWithinTheLimit() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, new FlatChains(0.23, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("SELL_STRADDLE", s.step().action());

        // five days before the front expiry the chain and clock have moved on
        LocalDate later = front().minusDays(5);
        VolSpreadStrategy.Decision close = s.stepWith(new FlatChains(0.23, later), Clock.fixed(later.atTime(15, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

        assertEquals("CLOSE", close.action(), close.summary());
        assertTrue(close.reason().contains("expiry within 7 days"));
        assertTrue(tracker.getPositions().values().stream().allMatch(p -> p.getQuantity() == 0));
    }

    @Test
    void hedgesNetDeltaBeyondTheBandWithSharesAndLeavesItAloneInside() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, new FlatChains(0.205, TODAY), 0.20, valuationWithDelta(-80.0), Clock.fixed(NOW, ZoneOffset.UTC));

        VolSpreadStrategy.Decision d = s.step();

        assertEquals(80, tracker.getNetQuantity("SPY"), "buy 80 shares to flatten -80 delta");
        assertTrue(d.orders().get(0).startsWith("BUY 80 SPY"), d.orders().toString());

        PositionTracker calm = new PositionTracker(FillRecorder.NONE);
        strategy(calm, new FlatChains(0.205, TODAY), 0.20, valuationWithDelta(30.0), Clock.fixed(NOW, ZoneOffset.UTC)).step();
        assertEquals(0, calm.getNetQuantity("SPY"), "30 delta is inside the 50 band");
    }

    @Test
    void waitsWithoutASpotAndNeverLegsInWhenTheFirstLegIsRefused() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy noSpot = new VolSpreadStrategy(manager(tracker), new FlatChains(0.23, TODAY), referenceSurface(0.20), null,
                () -> null, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        noSpot.allowSimulatedChains(true);
        assertEquals("WAIT", noSpot.step().action());

        TradingHalt halt = new TradingHalt();
        halt.halt("test");
        OrderManager haltedManager = new OrderManager(new PreTradeRiskFilter(1_000, 1e9, 1_000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.allowSimulated(), halt, null, 1);
        VolSpreadStrategy refused = new VolSpreadStrategy(haltedManager, new FlatChains(0.23, TODAY), referenceSurface(0.20), null,
                VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        refused.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = refused.step();

        assertEquals("SELL_STRADDLE_REJECTED", d.action());
        assertEquals(1, d.orders().size(), "the second leg is never sent after the first is refused");
        assertTrue(d.orders().get(0).contains("halted"), d.orders().get(0));
        assertTrue(tracker.getPositions().isEmpty());
    }

    // Found on review (2026-10-10): a rejected second leg left the first leg held, because the next step read one
    // leg as "an open straddle" and held it until the edge reverted. The Javadoc promised a flatten.

    /** Fills every order except puts, and refuses the {@code refusedCallCalls}-th call-option order (1-based), to model a venue that fails mid-straddle. */
    private static ExchangeTransport legFailingVenue(int refusedCallCall) {
        int[] callOrders = {0};
        return (order, sym, bid, ask) -> {
            boolean put = sym.length() > 9 && sym.charAt(sym.length() - 9) == 'P';
            boolean call = sym.length() > 9 && sym.charAt(sym.length() - 9) == 'C';
            if (put) {
                return new ExecutionResult(sym, 0, 0.0, false, "venue refused the put");
            }
            if (call && ++callOrders[0] == refusedCallCall) {
                return new ExecutionResult(sym, 0, 0.0, false, "venue refused the call");
            }
            return new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");
        };
    }

    private static VolSpreadStrategy strategyOn(ExchangeTransport transport, PositionTracker tracker) {
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1_000, 1e9, 1_000), transport, tracker,
                OrderManager.MarketDataPolicy.allowSimulated(), new TradingHalt(), null, 1);
        VolSpreadStrategy s = new VolSpreadStrategy(manager, new FlatChains(0.23, TODAY), referenceSurface(0.20), null,
                VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);
        return s;
    }

    private static long optionPositionsHeld(PositionTracker tracker) {
        return tracker.getPositions().values().stream().filter(p -> p.getQuantity() != 0).count();
    }

    @Test
    void aRefusedSecondLegIsFlattenedInTheSameStepRatherThanHeld() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategyOn(legFailingVenue(Integer.MAX_VALUE), tracker);

        VolSpreadStrategy.Decision d = s.step();

        assertEquals(0, optionPositionsHeld(tracker), "the call that filled was closed again: " + d.orders());
        assertTrue(d.action().endsWith("_REJECTED"), d.summary());
        assertTrue(d.reason().contains("flattened"), d.reason());
        assertEquals(3, d.orders().size(), "sell call, refused put, buy call back: " + d.orders());
    }

    @Test
    void aLoneLegWhoseFlattenWasRefusedIsFlattenedByTheNextStepWhateverTheEdge() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        // The venue refuses the put, and also refuses the first attempt to buy the call back.
        VolSpreadStrategy s = strategyOn(legFailingVenue(2), tracker);

        VolSpreadStrategy.Decision first = s.step();
        assertEquals(1, optionPositionsHeld(tracker), "the flatten was refused, so one leg is still held: " + first.orders());

        VolSpreadStrategy.Decision second = s.step();

        assertEquals(0, optionPositionsHeld(tracker), "the next step closes it even though the edge is still rich: " + second.summary());
        assertEquals("CLOSE", second.action());
        assertTrue(second.reason().contains("one leg"), second.reason());
    }

    @Test
    void parametersAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new VolSpreadStrategy.Params(0.0, 50, 7, 14, 1, "SSVI"));
        assertThrows(IllegalArgumentException.class, () -> new VolSpreadStrategy.Params(0.01, 50, 14, 7, 1, "SSVI"));
        assertThrows(IllegalArgumentException.class, () -> new VolSpreadStrategy.Params(0.01, 50, 7, 14, 0, "SSVI"));
        assertThrows(IllegalArgumentException.class, () -> new VolSpreadStrategy.Params(0.01, 50, 7, 14, 1, "HESTON"));
        assertEquals("SVI", new VolSpreadStrategy.Params(0.01, 50, 7, 14, 1, "svi").referenceModel());
    }

    @Test
    void allowedSideParsesAllVariantsSafely() {
        assertEquals(VolSpreadStrategy.AllowedSide.LONG_ONLY, VolSpreadStrategy.AllowedSide.parse("long_only"));
        assertEquals(VolSpreadStrategy.AllowedSide.LONG_ONLY, VolSpreadStrategy.AllowedSide.parse("long"));
        assertEquals(VolSpreadStrategy.AllowedSide.LONG_ONLY, VolSpreadStrategy.AllowedSide.parse("buy_only"));
        assertEquals(VolSpreadStrategy.AllowedSide.SHORT_ONLY, VolSpreadStrategy.AllowedSide.parse("short_only"));
        assertEquals(VolSpreadStrategy.AllowedSide.SHORT_ONLY, VolSpreadStrategy.AllowedSide.parse("short"));
        assertEquals(VolSpreadStrategy.AllowedSide.BOTH, VolSpreadStrategy.AllowedSide.parse("both"));
        assertEquals(VolSpreadStrategy.AllowedSide.BOTH, VolSpreadStrategy.AllowedSide.parse(null));
        assertEquals(VolSpreadStrategy.AllowedSide.BOTH, VolSpreadStrategy.AllowedSide.parse(""));
    }

    @Test
    void everyDecisionReachesTheListenerAndAFailingListenerNeverBreaksTheStep() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, new FlatChains(0.20, TODAY), 0.20, valuationWithDelta(0.0), Clock.fixed(NOW, ZoneOffset.UTC));
        java.util.List<VolSpreadStrategy.Decision> seen = new java.util.ArrayList<>();
        s.setDecisionListener(seen::add);

        VolSpreadStrategy.Decision d = s.step();

        assertEquals(1, seen.size());
        assertSame(d, seen.get(0));

        s.setDecisionListener(decision -> { throw new IllegalStateException("disk full"); });
        VolSpreadStrategy.Decision again = assertDoesNotThrow(s::step);
        assertEquals(again, s.lastDecision().orElseThrow());
    }

    @Test
    void aMisspeltSideIsAnErrorNotSilentlyTheMostPermissiveChoice() {
        // "long_onyl" used to become BOTH, which is the setting that allows short straddles
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> VolSpreadStrategy.AllowedSide.parse("long_onyl"));
        assertTrue(e.getMessage().contains("long_onyl") && e.getMessage().contains("long_only"), e.getMessage());
    }

    @Test
    void theHoldForARichMarketNamesTheSettingAndDoesNotAssertAnAccountRuleThatWasNeverChecked() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy.Params p = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false, VolSpreadStrategy.AllowedSide.LONG_ONLY);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new FlatChains(0.25, TODAY), referenceSurface(0.20),
                valuationWithDelta(0.0), VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, p, Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);

        String reason = s.step().reason();

        assertTrue(reason.contains("strategy.side"), reason);
        assertFalse(reason.contains("Level 4") || reason.contains("forbids"), "no unverified claim about Alpaca's rules: " + reason);
    }

    @Test
    void longOnlyRefusesToShortRichMarketVol() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        // Market vol 25%, reference vol 20% -> market is rich (+5 pts edge > 1 pt threshold)
        // With default BOTH, this would open SHORT_STRADDLE.
        // With LONG_ONLY, it must HOLD.
        VolSpreadStrategy.Params p = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false, VolSpreadStrategy.AllowedSide.LONG_ONLY);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new FlatChains(0.25, TODAY), referenceSurface(0.20),
                valuationWithDelta(0.0), VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, p, Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = s.step();
        assertEquals("HOLD", d.action());
        assertTrue(d.reason().contains("short straddle not permitted"), "Decision reason: " + d.reason());
        assertEquals(0, optionPositionsHeld(tracker));
    }

    @Test
    void legQuoteSourceOverridesChainsOptionQuote() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, new FlatChains(0.20, TODAY), 0.20, valuationWithDelta(0.0), Clock.fixed(NOW, ZoneOffset.UTC));

        // Inject custom leg quote source
        MarketSnapshot freshSnapshot = new MarketSnapshot("SPY261016C00100000", 1.50, 1.55, 1.525, 100L, NOW, NOW, 0L, "ALPACA", MarketDataStatus.LIVE);
        s.setLegQuoteSource(occ -> occ.equals("SPY261016C00100000") ? Optional.of(freshSnapshot) : Optional.empty());

        MarketSnapshot resolved = s.resolveQuote("SPY261016C00100000");
        assertNotNull(resolved);
        assertEquals("ALPACA", resolved.source());
        assertEquals(1.525, resolved.last(), 1e-6);
    }

    // ------------------------------------------------------------------ supervised test straddle

    private static final Instant AFTER_HOURS = Instant.parse("2026-10-08T22:00:00Z"); // 18:00 in New York
    private static final Instant SATURDAY = Instant.parse("2026-10-10T15:00:00Z");

    @Test
    void everyStrategyStepIsTimed() {
        VolSpreadStrategy s = strategy(new PositionTracker(FillRecorder.NONE), new FlatChains(0.20, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));
        var before = com.sbk.optionspricer.metrics.LatencyTracker.shared().snapshot().get("strategy.step");

        s.step();

        var after = com.sbk.optionspricer.metrics.LatencyTracker.shared().snapshot().get("strategy.step");
        assertEquals((before == null ? 0L : before.count()) + 1, after.count());
    }

    @Test
    void aSupervisedTestIsRefusedOnAnExchangeHolidayAndAfterAnEarlyClose() {
        for (String instant : new String[] {"2026-11-26T15:00:00Z" /* Thanksgiving 10:00 */, "2026-11-27T19:30:00Z" /* 14:30, after the 13:00 close */}) {
            PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
            VolSpreadStrategy s = strategy(tracker, new FlatChains(0.20, TODAY), 0.20, null, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

            VolSpreadStrategy.Decision d = s.supervisedBuyStraddle(true);

            assertEquals("SUPERVISED_REFUSED", d.action(), instant + ": " + d.summary());
            assertTrue(d.reason().contains("trading hours"), d.reason());
            assertTrue(tracker.getPositions().isEmpty());
        }
    }

    @Test
    void aSupervisedDryRunNamesTheLegsAndTheDebitAndSendsNothing() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, new FlatChains(0.20, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));

        VolSpreadStrategy.Decision d = s.supervisedBuyStraddle(false);

        assertEquals("SUPERVISED_DRY_RUN", d.action(), d.summary());
        assertTrue(d.orders().isEmpty(), "a dry run places nothing: " + d.orders());
        assertTrue(d.reason().contains("SPY") && d.reason().contains("debit"), d.reason());
        assertTrue(tracker.getPositions().isEmpty());
        assertTrue(s.lastDecision().isEmpty(), "a dry run is not a strategy decision and is not journaled");
    }

    @Test
    void aSupervisedStraddleIsRefusedOutsideRegularHoursAndAtTheWeekend() {
        for (Instant closed : List.of(AFTER_HOURS, SATURDAY, Instant.parse("2026-10-08T13:00:00Z") /* 09:00 New York */)) {
            PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
            VolSpreadStrategy s = strategy(tracker, new FlatChains(0.20, TODAY), 0.20, null, Clock.fixed(closed, ZoneOffset.UTC));

            VolSpreadStrategy.Decision d = s.supervisedBuyStraddle(true);

            assertEquals("SUPERVISED_REFUSED", d.action(), closed + ": " + d.summary());
            assertTrue(d.reason().contains("regular trading hours"), d.reason());
            assertTrue(d.orders().isEmpty() && tracker.getPositions().isEmpty(), "nothing may be sent at " + closed);
        }
    }

    @Test
    void aConfirmedSupervisedStraddleBuysOneCallAndOnePutThroughTheOrderManagerAndTheTracker() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, new FlatChains(0.20, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));

        VolSpreadStrategy.Decision d = s.supervisedBuyStraddle(true);

        assertEquals("SUPERVISED_BUY_STRADDLE", d.action(), d.summary());
        assertEquals(1, d.straddleSign());
        assertEquals(2, d.orders().size(), d.orders().toString());
        assertTrue(d.orders().stream().allMatch(o -> o.startsWith("BUY 1 ") && o.endsWith("filled")), d.orders().toString());
        assertEquals(2, tracker.getPositions().size());
        assertTrue(tracker.getPositions().keySet().stream().allMatch(sym -> tracker.getNetQuantity(sym) == 1), tracker.getPositions().toString());
        assertEquals(d, s.lastDecision().orElseThrow(), "a placed straddle is a decision and is journaled");

        VolSpreadStrategy.Decision again = s.supervisedBuyStraddle(true);
        assertEquals("SUPERVISED_REFUSED", again.action(), again.summary());
        assertTrue(again.reason().contains("already open"), again.reason());
        assertEquals(2, tracker.getPositions().size(), "no second straddle");

        // and from here the ordinary step manages it: it is the strategy's open straddle now
        VolSpreadStrategy.Decision next = s.step();
        assertEquals(1, next.straddleSign(), next.summary());
    }

    @Test
    void aSupervisedStraddleIsRefusedForAShortOnlyStrategyAndWhileTheSpotIsStale() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy.Params shortOnly = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false, VolSpreadStrategy.AllowedSide.SHORT_ONLY);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new FlatChains(0.20, TODAY), referenceSurface(0.20), null,
                VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, shortOnly, Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);
        VolSpreadStrategy.Decision d = s.supervisedBuyStraddle(true);
        assertEquals("SUPERVISED_REFUSED", d.action(), d.summary());
        assertTrue(d.reason().contains("short"), d.reason());

        MarketSnapshot staleSpot = new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, NOW, NOW, 0L, "TEST", MarketDataStatus.STALE);
        VolSpreadStrategy stale = new VolSpreadStrategy(manager(tracker), new FlatChains(0.20, TODAY), referenceSurface(0.20), null,
                () -> staleSpot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        stale.allowSimulatedChains(true);
        VolSpreadStrategy.Decision st = stale.supervisedBuyStraddle(true);
        assertEquals("SUPERVISED_REFUSED", st.action(), st.summary());
        assertTrue(st.reason().contains("spot"), st.reason());
        assertTrue(tracker.getPositions().isEmpty());
    }

    // ------------------------------------------------------------------ feed-age telemetry

    /** Generated chains that report a feed time and can serve their quotes with an unknown source time. */
    private static final class AgedChains implements OptionMarketData {
        private final FlatChains inner = new FlatChains(0.20, TODAY);
        private final Instant chainAsOf;
        private final boolean unknownQuoteTime;

        AgedChains(Instant chainAsOf, boolean unknownQuoteTime) {
            this.chainAsOf = chainAsOf;
            this.unknownQuoteTime = unknownQuoteTime;
        }

        @Override public List<LocalDate> loadedExpiries() { return inner.loadedExpiries(); }
        @Override public Optional<OptionChain> chain(LocalDate expiry) { return inner.chain(expiry); }
        @Override public Optional<Instant> chainAsOf(LocalDate expiry) { return Optional.ofNullable(chainAsOf); }
        @Override public Optional<MarketSnapshot> optionQuote(String contractSymbol) {
            return inner.optionQuote(contractSymbol).map(q -> unknownQuoteTime
                    // what OptionQuoteSnapshots produces when the feed gave no time: an age equal to the epoch milliseconds
                    ? new MarketSnapshot(q.symbol(), q.bid(), q.ask(), q.last(), q.volume(), NOW, Instant.EPOCH, NOW.toEpochMilli(),
                            q.source(), q.status(), q.instrument())
                    : q);
        }
    }

    @Test
    void aDecisionRecordsTheAgeOfTheSpotTheChainAndTheOptionQuotesFromTheirOwnFeedTimes() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        MarketSnapshot spot7s = new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, NOW, NOW.minusSeconds(7), 7_000L, "TEST", MarketDataStatus.LIVE);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new AgedChains(NOW.minusSeconds(100), false), referenceSurface(0.20), null,
                () -> spot7s, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = s.step();

        assertEquals("HOLD", d.action(), d.summary());
        assertEquals(7.0, d.spotAgeSeconds(), 1e-9);
        assertEquals(100.0, d.chainAgeSeconds(), 1e-9, "the chain's age is the feed's as-of time against now");
        assertEquals(0.0, d.optionQuoteAgeSeconds(), 1e-9);
    }

    @Test
    void anUnknownFeedTimeIsRecordedAsUnknownNotAsAnAgeOfFiftySixYears() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new AgedChains(null, true), referenceSurface(0.20), null,
                VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, params(), Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = s.step();

        assertTrue(Double.isNaN(d.chainAgeSeconds()), "no chain time: unknown, and not borrowed from a quote: " + d.chainAgeSeconds());
        assertTrue(Double.isNaN(d.optionQuoteAgeSeconds()), "an epoch source time is 'unknown': " + d.optionQuoteAgeSeconds());
    }

    @Test
    void aStepAsksForEachLegQuoteOnceEvenThoughTheAgesAreRecorded() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        VolSpreadStrategy s = strategy(tracker, new FlatChains(0.20, TODAY), 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));
        java.util.List<String> asked = new java.util.ArrayList<>();
        s.setLegQuoteSource(sym -> { asked.add(sym); return Optional.empty(); }); // the venue; falls back to the chains

        VolSpreadStrategy.Decision d = s.step();

        assertEquals("HOLD", d.action(), d.summary());
        assertEquals(2, asked.size(), "one request per leg per step, not one for the age and another for the edge: " + asked);
    }

    private static VolatilitySurfaceSource referenceSurfaceWithRmse(double vol, double rmse) {
        FlatChains source = new FlatChains(vol, TODAY);
        List<OptionChain> chains = new ArrayList<>();
        for (LocalDate e : source.loadedExpiries()) chains.add(source.chain(e).orElseThrow());
        SurfaceFitter.Fit base = SurfaceFitter.fitSsvi(SurfaceFitter.extractPoints(chains, R, Q, TODAY).points());
        SurfaceFitter.Fit ssvi = new SurfaceFitter.Fit(base.model(), base.strikes(), base.expiries(), base.vols(),
                base.points(), rmse, base.quotesUsed(), base.parameters(), base.noArbitrageConditionsHold(), base.warnings(), base.fittedExpiries());
        var snapshot = new VolatilitySurfaceSource.Snapshot(NOW, "SPY", "SYNTHETIC", false, 100.0, ssvi, null, null, 0, List.of());
        return new VolatilitySurfaceSource() {
            @Override public Optional<Snapshot> latest() { return Optional.of(snapshot); }
            @Override public Status status() { return new Status(State.READY, "ok", NOW); }
        };
    }

    @Test
    void dynamicRmseHurdleBlocksOpeningWhenEdgeIsInsideSurfaceFitUncertainty() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        // market at 21.5% vol, reference at 20%: edge is 1.5 vol points
        // With static volEdge = 0.01 (1.0 pt), this would normally trigger SELL_STRADDLE.
        // But with rmse = 0.01 (1.0 pt) and rmseMultiplier = 2.0 (2.0 pt hurdle), the edge is within fitting error.
        VolSpreadStrategy.Params dynamicParams = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false,
                VolSpreadStrategy.AllowedSide.BOTH, 2.0);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new FlatChains(0.215, TODAY),
                referenceSurfaceWithRmse(0.20, 0.01), null, VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, dynamicParams, Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = s.step();

        assertEquals("HOLD", d.action(), d.summary());
        assertTrue(d.reason().contains("surface residual uncertainty hurdle"), "Reason must mention surface residual uncertainty: " + d.reason());
        assertTrue(tracker.getPositions().isEmpty());
    }

    @Test
    void dynamicRmseHurdleAllowsOpeningWhenEdgeExceedsSurfaceFitUncertainty() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        // market at 23.5% vol, reference at 20%: edge is 3.5 vol points
        // With rmse = 0.01 (1.0 pt) and rmseMultiplier = 2.0 (2.0 pt hurdle), edge 3.5 > 2.0 hurdle -> trades!
        VolSpreadStrategy.Params dynamicParams = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false,
                VolSpreadStrategy.AllowedSide.BOTH, 2.0);
        VolSpreadStrategy s = new VolSpreadStrategy(manager(tracker), new FlatChains(0.235, TODAY),
                referenceSurfaceWithRmse(0.20, 0.01), null, VolSpreadStrategyTest::spot, tracker, "SPY", R, Q, dynamicParams, Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = s.step();

        assertEquals("SELL_STRADDLE", d.action(), d.summary());
        assertEquals(-1, d.straddleSign());
    }

    @Test
    void aDecisionRecordsTheWiderOfTheTwoLegSpreadsInTheGatesOwnUnits() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        FlatChains market = new FlatChains(0.20, TODAY);
        VolSpreadStrategy s = strategy(tracker, market, 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC));

        VolSpreadStrategy.Decision d = s.step();

        assertEquals("HOLD", d.action(), d.summary());
        double expected = 0.0;
        for (OptionType type : OptionType.values()) {
            MarketSnapshot q = market.optionQuote(OccSymbol.format("SPY", d.expiry(), type, d.strike())).orElseThrow();
            expected = Math.max(expected, (q.ask() - q.bid()) / ((q.ask() + q.bid()) / 2.0) * 10_000.0);
        }
        assertTrue(expected > 0.0, "the generated quotes have a spread");
        assertEquals(expected, d.optionSpreadBps(), 1e-6);

        VolSpreadStrategy.Decision waiting = strategy(tracker, market, 0.20, null, Clock.fixed(NOW, ZoneOffset.UTC)).supervisedBuyStraddle(false);
        assertEquals(expected, waiting.optionSpreadBps(), 1e-6, "a supervised dry run shows the spread it would have faced");
    }

    // ------------------------------------------------------------------ closing in one order

    /** Accepts every order, and every multi-leg order unless told not to; remembers what it was asked. */
    private static final class MlegTransport implements ExchangeTransport {
        final List<List<Boolean>> multiLegClosing = new ArrayList<>();
        final List<Double> multiLegNets = new ArrayList<>();
        boolean acceptMultiLeg = true;
        int singleLegOrders;

        @Override public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
            singleLegOrders++;
            return new ExecutionResult(symbol, order.quantity(), order.price(), true, "Executed");
        }

        @Override public ExecutionResult transmitMultiLeg(List<Order> legs, List<String> symbols, List<Double> bids, List<Double> asks,
                                                          double net, List<Boolean> closing) {
            multiLegClosing.add(closing);
            multiLegNets.add(net);
            if (!acceptMultiLeg) {
                return new ExecutionResult(symbols.get(0), 0, 0.0, false, "Alpaca order x canceled, nothing filled: no multi-leg fill within 10 s");
            }
            return new ExecutionResult(symbols.get(0), legs.get(0).quantity(), net, true, "Multi-leg executed");
        }
    }

    private static VolSpreadStrategy mlegStrategy(PositionTracker tracker, MlegTransport transport, boolean useMultiLeg) {
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1_000, 1e9, 1_000), transport, tracker,
                OrderManager.MarketDataPolicy.allowSimulated(), new TradingHalt(), null, 1);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, useMultiLeg);
        VolSpreadStrategy s = new VolSpreadStrategy(manager, new FlatChains(0.20, TODAY), referenceSurface(0.20), null, VolSpreadStrategyTest::spot,
                tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        s.allowSimulatedChains(true);
        return s;
    }

    @Test
    void aMultiLegStraddleIsClosedInOneOrderMarkedAsClosing() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        MlegTransport transport = new MlegTransport();
        VolSpreadStrategy s = mlegStrategy(tracker, transport, true);
        assertEquals("SUPERVISED_BUY_STRADDLE", s.supervisedBuyStraddle(true).action());

        VolSpreadStrategy.Decision close = s.step();

        assertEquals("CLOSE", close.action(), close.summary());
        assertEquals(1, close.orders().size(), "one order for both legs: " + close.orders());
        assertTrue(close.orders().get(0).startsWith("SELL 1 ") && close.orders().get(0).contains("MLEG filled"), close.orders().toString());
        assertEquals(List.of(List.of(false, false), List.of(true, true)), transport.multiLegClosing, "the open, then the close");
        assertEquals(0, transport.singleLegOrders, "no leg-by-leg order was needed");
        assertTrue(tracker.getPositions().keySet().stream().allMatch(sym -> tracker.getNetQuantity(sym) == 0), tracker.getPositions().toString());
    }

    @Test
    void ifTheAtomicCloseIsNotFilledTheLegsAreClosedOneByOneAsBefore() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        MlegTransport transport = new MlegTransport();
        VolSpreadStrategy s = mlegStrategy(tracker, transport, true);
        s.supervisedBuyStraddle(true);
        transport.acceptMultiLeg = false;

        VolSpreadStrategy.Decision close = s.step();

        assertEquals("CLOSE", close.action(), close.summary());
        assertTrue(close.orders().get(0).contains("MLEG rejected"), "the failed atomic close is reported: " + close.orders());
        assertEquals(2, transport.singleLegOrders, "then each leg sold on its own");
        assertTrue(close.orders().stream().filter(o -> o.startsWith("SELL 1 ") && o.endsWith("filled")).count() == 2, close.orders().toString());
        assertTrue(tracker.getPositions().keySet().stream().allMatch(sym -> tracker.getNetQuantity(sym) == 0), "flat: " + tracker.getPositions());
    }

    @Test
    void withMultiLegOffTheCloseIsLegByLegAsItAlwaysWas() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        MlegTransport transport = new MlegTransport();
        VolSpreadStrategy s = mlegStrategy(tracker, transport, false);
        s.supervisedBuyStraddle(true); // opens leg by leg: multi-leg is off

        VolSpreadStrategy.Decision close = s.step();

        assertEquals("CLOSE", close.action(), close.summary());
        assertTrue(transport.multiLegClosing.isEmpty(), "no multi-leg order at all: " + transport.multiLegClosing);
        assertEquals(4, transport.singleLegOrders, "two to open and two to close");
    }

    @Test
    void anAtomicCloseIsPricedAtTheBidsLikeASingleSellAndNotAtTheMid() {
        // Live on 2026-10-20 the atomic close asked for 24.54, the sum of the mids, while the bids summed to about 24.27: Alpaca
        // accepted it and it never filled. A sell is priced at the touch (the bid), and a buy-back at the ask.
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        MlegTransport transport = new MlegTransport();
        FlatChains market = new FlatChains(0.20, TODAY);
        VolSpreadStrategy s = mlegStrategy(tracker, transport, true);
        s.supervisedBuyStraddle(true);

        VolSpreadStrategy.Decision close = s.step();

        assertEquals("CLOSE", close.action(), close.summary());
        double bids = 0.0, mids = 0.0;
        for (OptionType type : OptionType.values()) {
            MarketSnapshot q = market.optionQuote(OccSymbol.format("SPY", close.expiry(), type, close.strike())).orElseThrow();
            bids += q.bid();
            mids += q.last();
        }
        assertTrue(bids < mids, "the generated quotes have a spread");
        assertEquals(bids, transport.multiLegNets.get(1), 1e-9, "the close is offered at the sum of the bids: " + transport.multiLegNets);
    }
}
