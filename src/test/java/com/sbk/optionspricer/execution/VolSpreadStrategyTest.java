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
}
