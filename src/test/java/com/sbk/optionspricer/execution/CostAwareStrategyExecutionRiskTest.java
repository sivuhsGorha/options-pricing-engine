package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.market.OptionMarketData;
import com.sbk.optionspricer.risk.FillRecorder;
import com.sbk.optionspricer.volatility.FairVolProvider;
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
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates Phase 3 (Cost-aware dynamic edge & break-even hurdle) and Phase 6 (Atomic Multi-Leg,
 * Early Assignment and Pin Risk monitors).
 */
public class CostAwareStrategyExecutionRiskTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final double R = 0.05, Q = 0.0;

    private static LocalDate front() {
        return OptionChainProvider.thirdFridays(TODAY, 3).stream().filter(e -> !e.isBefore(TODAY.plusDays(14))).findFirst().orElseThrow();
    }

    private static MarketSnapshot spot(double price) {
        return new MarketSnapshot("SPY", price - 0.01, price + 0.01, price, 5000L, NOW, NOW, 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static OrderManager testManager(PositionTracker tracker, ExchangeTransport transport) {
        return new OrderManager(new PreTradeRiskFilter(1_000, 1e9, 1_000), transport, tracker,
                OrderManager.MarketDataPolicy.allowSimulated(), new TradingHalt(), null, 1);
    }

    private static VolatilitySurfaceSource flatReferenceSurface(double vol) {
        var provider = new com.sbk.optionspricer.SyntheticOptionChainProvider(100.0, vol, R, Q, Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        List<LocalDate> expiries = OptionChainProvider.thirdFridays(TODAY, 3);
        List<OptionChain> chains = new ArrayList<>();
        for (LocalDate e : expiries) chains.add(provider.getOptionChain("SPY", e));
        SurfaceFitter.Fit ssvi = SurfaceFitter.fitSsvi(SurfaceFitter.extractPoints(chains, R, Q, TODAY).points());
        var snapshot = new VolatilitySurfaceSource.Snapshot(NOW, "SPY", "SYNTHETIC", false, 100.0, ssvi, null, null, 0, List.of());
        return new VolatilitySurfaceSource() {
            @Override public Optional<Snapshot> latest() { return Optional.of(snapshot); }
            @Override public Status status() { return new Status(State.READY, "ok", NOW); }
        };
    }

    private static double[] bsAtmQuotes(LocalDate expiry, double vol, double spread) {
        double t = TimeConventions.yearFraction(TODAY, expiry);
        double callMid = BlackScholesPricer.price(OptionType.CALL, 100.0, 100.0, t, R, vol, Q);
        double putMid = BlackScholesPricer.price(OptionType.PUT, 100.0, 100.0, t, R, vol, Q);
        double half = spread / 2.0;
        return new double[]{callMid - half, callMid + half, putMid - half, putMid + half};
    }

    /** Custom market data provider that allows configuring bid/ask spreads directly. */
    private static final class ConfigurableMarketData implements OptionMarketData {
        private final LocalDate frontExpiry;
        private final double strike;
        private double callBid, callAsk, callIv;
        private double putBid, putAsk, putIv;

        ConfigurableMarketData(LocalDate frontExpiry, double strike, double callBid, double callAsk, double callIv,
                               double putBid, double putAsk, double putIv) {
            this.frontExpiry = frontExpiry;
            this.strike = strike;
            this.callBid = callBid;
            this.callAsk = callAsk;
            this.callIv = callIv;
            this.putBid = putBid;
            this.putAsk = putAsk;
            this.putIv = putIv;
        }

        void updateQuotes(double cBid, double cAsk, double pBid, double pAsk) {
            this.callBid = cBid;
            this.callAsk = cAsk;
            this.putBid = pBid;
            this.putAsk = pAsk;
        }

        @Override public List<LocalDate> loadedExpiries() { return List.of(frontExpiry); }

        @Override public Optional<OptionChain> chain(LocalDate expiry) {
            if (!expiry.equals(frontExpiry)) return Optional.empty();
            List<OptionQuote> quotes = List.of(
                    new OptionQuote("SPY", expiry, strike, OptionType.CALL, callBid, callAsk, callIv, 100L, 500L),
                    new OptionQuote("SPY", expiry, strike, OptionType.PUT, putBid, putAsk, putIv, 100L, 500L)
            );
            return Optional.of(new OptionChain("SPY", expiry, 100.0, quotes));
        }

        @Override public Optional<MarketSnapshot> optionQuote(String contractSymbol) {
            var parsed = OccSymbol.parse(contractSymbol).orElseThrow();
            double b = parsed.type() == OptionType.CALL ? callBid : putBid;
            double a = parsed.type() == OptionType.CALL ? callAsk : putAsk;
            double mid = (b + a) / 2.0;
            return Optional.of(new MarketSnapshot(contractSymbol, b, a, mid, 500L, NOW, NOW, 0L, "TEST", MarketDataStatus.LIVE));
        }
    }

    @Test
    void rejectsWideSpreadDueToHighBreakEvenHurdleEvenWithSubstantialEdge() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        ExchangeTransport transport = (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");

        // Reference is 20% vol, market is 23% vol (3 points nominal edge)
        // With a wide spread of $3.00: half-spread = $1.50 per leg
        // Dollar friction = 1.50 + 1.50 + 0.013 = $3.013. Straddle Vega ~ 23.
        // Break-even hurdle ~ 3.013 / 23 = 13.1 vol points!
        // 3.00 pts edge is smaller than 13.1 pts, so it must be REJECTED (HOLD).
        double[] wide = bsAtmQuotes(expiry, 0.23, 3.00);
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, wide[0], wide[1], 0.23, wide[2], wide[3], 0.23);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false);

        VolSpreadStrategy strategy = new VolSpreadStrategy(testManager(tracker, transport), market, flatReferenceSurface(0.20),
                null, () -> spot(100.0), tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy.allowSimulatedChains(true);

        VolSpreadStrategy.Decision decision = strategy.step();
        assertEquals("HOLD", decision.action(), decision.summary());
        assertTrue(decision.reason().contains("break-even hurdle"), decision.reason());
        assertTrue(tracker.getPositions().isEmpty(), "No positions opened due to wide spread");

        // Now tighten spreads to penny-wide ($0.02 spread -> half-spread $0.01)
        // Dollar friction = 0.01 + 0.01 + 0.013 = $0.033. Break-even hurdle ~ 0.033 / 23 = 0.14 vol points.
        // 3.00 pts edge easily clears the hurdle -> SELL_STRADDLE opens!
        double[] tight = bsAtmQuotes(expiry, 0.23, 0.02);
        market.updateQuotes(tight[0], tight[1], tight[2], tight[3]);
        VolSpreadStrategy.Decision openDecision = strategy.step();
        assertEquals("SELL_STRADDLE", openDecision.action(), openDecision.summary());
        assertFalse(tracker.getPositions().isEmpty(), "Straddle opened after spreads tightened");
    }

    @Test
    void submitsAtomicMultiLegOrderWhenEnabledEliminatingLegging() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        List<String> transmittedOrders = new ArrayList<>();

        ExchangeTransport multiLegTransport = new ExchangeTransport() {
            @Override
            public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
                fail("Single leg transmit should not be called when useMultiLeg is true");
                return new ExecutionResult(symbol, 0, 0.0, false, "Failed");
            }

            @Override
            public ExecutionResult transmitMultiLeg(List<Order> legs, List<String> symbols, List<Double> bids, List<Double> asks, double netLimitPrice) {
                assertEquals(2, legs.size());
                assertEquals(2, symbols.size());
                transmittedOrders.add("MLEG: " + symbols.get(0) + " & " + symbols.get(1));
                return new ExecutionResult(symbols.get(0), legs.get(0).quantity(), netLimitPrice, true, "Multi-leg executed atomically");
            }
        };

        double[] tight = bsAtmQuotes(expiry, 0.23, 0.02);
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, tight[0], tight[1], 0.23, tight[2], tight[3], 0.23);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, true);

        VolSpreadStrategy strategy = new VolSpreadStrategy(testManager(tracker, multiLegTransport), market, flatReferenceSurface(0.20),
                null, () -> spot(100.0), tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy.allowSimulatedChains(true);

        VolSpreadStrategy.Decision decision = strategy.step();
        assertEquals("SELL_STRADDLE", decision.action());
        assertEquals(1, transmittedOrders.size());
        assertTrue(decision.orders().get(0).contains("MLEG filled"), decision.orders().get(0));
        assertEquals(-1, tracker.getNetQuantity(OccSymbol.format("SPY", expiry, OptionType.CALL, 100.0)));
        assertEquals(-1, tracker.getNetQuantity(OccSymbol.format("SPY", expiry, OptionType.PUT, 100.0)));
    }

    @Test
    void triggersPinRiskCloseWhenNearExpiryAndSpotPinsStrike() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        ExchangeTransport transport = (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");

        double[] tight = bsAtmQuotes(expiry, 0.23, 0.02);
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, tight[0], tight[1], 0.23, tight[2], tight[3], 0.23);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 0, 14, 1, "SSVI", 0.65, false);

        VolSpreadStrategy strategy = new VolSpreadStrategy(testManager(tracker, transport), market, flatReferenceSurface(0.20),
                null, () -> spot(100.0), tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy.allowSimulatedChains(true);

        // Open straddle
        assertEquals("SELL_STRADDLE", strategy.step().action());

        // Fast-forward to 1 day before expiry with spot at 100.20 (within 0.5% pin band of strike 100)
        LocalDate nearExpiry = expiry.minusDays(1);
        Instant nearExpiryInstant = nearExpiry.atTime(15, 0).toInstant(ZoneOffset.UTC);
        Clock nearClock = Clock.fixed(nearExpiryInstant, ZoneOffset.UTC);

        VolSpreadStrategy.Decision closeDecision = strategy.stepWith(market, nearClock);
        assertEquals("CLOSE", closeDecision.action());
        assertTrue(closeDecision.reason().contains("pin risk"), closeDecision.reason());
        assertTrue(tracker.getPositions().values().stream().allMatch(p -> p.getQuantity() == 0), "Straddle closed due to pin risk");
    }

    @Test
    void triggersEarlyAssignmentCloseWhenShortOptionBecomesDeepItmWithLowExtrinsicValue() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        ExchangeTransport transport = (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");

        double[] tight = bsAtmQuotes(expiry, 0.23, 0.02);
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, tight[0], tight[1], 0.23, tight[2], tight[3], 0.23);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 0, 14, 1, "SSVI", 0.65, false);

        double[] currentSpot = new double[]{100.0};
        VolSpreadStrategy strategy = new VolSpreadStrategy(testManager(tracker, transport), market, flatReferenceSurface(0.20),
                null, () -> spot(currentSpot[0]), tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy.allowSimulatedChains(true);

        // Open short straddle
        assertEquals("SELL_STRADDLE", strategy.step().action());

        // Spot rallies massively to 120.0: Call is deep ITM (intrinsic = $20.00)
        // Call quote is at 20.05 (extrinsic time value is only $0.05 < $0.10)
        currentSpot[0] = 120.0;
        market.updateQuotes(20.04, 20.06, 0.01, 0.03);

        VolSpreadStrategy.Decision closeDecision = strategy.stepWith(market);
        assertEquals("CLOSE", closeDecision.action());
        assertTrue(closeDecision.reason().contains("early assignment risk"), closeDecision.reason());
        assertTrue(tracker.getPositions().values().stream().allMatch(p -> p.getQuantity() == 0), "Short straddle closed to prevent assignment");
    }

    @Test
    void aMultiLegFillIsBookedSoThatTheLegsAddUpToWhatTheVenueActuallyCharged() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        double netFill = 7.00; // the venue's net debit for the pair
        ExchangeTransport transport = new ExchangeTransport() {
            @Override public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
                throw new AssertionError("a multi-leg order must not go leg by leg");
            }
            @Override public ExecutionResult transmitMultiLeg(List<Order> legs, List<String> symbols, List<Double> bids, List<Double> asks, double net) {
                return new ExecutionResult(symbols.get(0), legs.get(0).quantity(), netFill, true, "filled");
            }
        };
        double[] q = bsAtmQuotes(expiry, 0.23, 0.02);
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, q[0], q[1], 0.23, q[2], q[3], 0.23);
        String call = OccSymbol.format("SPY", expiry, OptionType.CALL, 100.0);
        String put = OccSymbol.format("SPY", expiry, OptionType.PUT, 100.0);
        MarketSnapshot callQuote = market.optionQuote(call).orElseThrow();
        MarketSnapshot putQuote = market.optionQuote(put).orElseThrow();
        double limitSum = callQuote.last() + putQuote.last();
        assertNotEquals(netFill, limitSum, 0.5, "the test needs a fill that differs from the quoted mids");

        OrderManager.OrderDecision d = testManager(tracker, transport).submitMultiLeg(
                List.of(new Order(0, true, 1, callQuote.last()), new Order(0, true, 1, putQuote.last())),
                List.of(callQuote, putQuote), limitSum);

        assertTrue(d.accepted(), d.message());
        double booked = tracker.getAverageCost(call) + tracker.getAverageCost(put);
        assertEquals(netFill, booked, 1e-9, "the book's cost must equal the venue's net debit, not the limit prices");
        assertEquals(callQuote.last() / putQuote.last(), tracker.getAverageCost(call) / tracker.getAverageCost(put), 1e-9,
                "split in proportion to the legs' quoted prices");
    }

    @Test
    void theHurdleCoversTheExitAsWellAsTheEntry() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        ExchangeTransport transport = (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");

        // Market 23% against a 20% reference: 3 points of edge. Choose the spread so that the one-way cost of
        // opening is 2 vol points (the edge clears it) but opening AND closing costs 4 (the edge does not).
        double t = TimeConventions.yearFraction(TODAY, expiry);
        double straddleVega = 2.0 * BlackScholesPricer.vega(100.0, 100.0, t, R, 0.23, Q);
        double fees = 2.0 * 0.65 / 100.0;
        double spread = 0.02 * straddleVega - fees;
        double[] q = bsAtmQuotes(expiry, 0.23, spread);
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, q[0], q[1], 0.23, q[2], q[3], 0.23);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false);
        VolSpreadStrategy strategy = new VolSpreadStrategy(testManager(tracker, transport), market, flatReferenceSurface(0.20),
                null, () -> spot(100.0), tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = strategy.step();

        assertEquals("HOLD", d.action(), d.summary());
        assertTrue(d.reason().contains("break-even hurdle"), d.reason());
        assertTrue(tracker.getPositions().isEmpty());
    }

    @Test
    void anSsviReferenceStillReportsTheRealizedVolForecastAsAShadowReadout() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        ExchangeTransport transport = (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");
        double[] q = bsAtmQuotes(expiry, 0.20, 0.02); // market on the reference: no edge
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, q[0], q[1], 0.20, q[2], q[3], 0.20);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "SSVI", 0.65, false);
        FairVolProvider forecast = (sym, exp, strk, t) -> OptionalDouble.of(0.18);
        VolSpreadStrategy strategy = new VolSpreadStrategy(testManager(tracker, transport), market, flatReferenceSurface(0.20),
                forecast, null, () -> spot(100.0), tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy.allowSimulatedChains(true);

        VolSpreadStrategy.Decision d = strategy.step();

        assertEquals("HOLD", d.action(), d.summary());
        assertEquals(0.20, d.referenceIv(), 0.003, "the SSVI surface stays the reference that trades");
        assertTrue(d.reason().contains("RV forecast 18.0%"), d.reason());
        assertTrue(d.reason().contains("VRP +2.0 pts"), d.reason());
        assertTrue(tracker.getPositions().isEmpty());
    }

    @Test
    void aFairVolReferenceWithoutAProviderIsRefusedNotSilentlyReplacedBySsvi() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        ExchangeTransport transport = (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");
        VolSpreadStrategy.Params har = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "HAR", 0.65, false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new VolSpreadStrategy(testManager(tracker, transport), new ConfigurableMarketData(front(), 100.0, 1, 2, 0.2, 1, 2, 0.2),
                        flatReferenceSurface(0.20), null, () -> spot(100.0), tracker, "SPY", R, Q, har, Clock.fixed(NOW, ZoneOffset.UTC)));

        assertTrue(e.getMessage().contains("HAR"), e.getMessage());
    }

    @Test
    void referencesIndependentFairVolProviderWhenConfigured() {
        LocalDate expiry = front();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        ExchangeTransport transport = (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");

        double[] tight = bsAtmQuotes(expiry, 0.23, 0.02);
        ConfigurableMarketData market = new ConfigurableMarketData(expiry, 100.0, tight[0], tight[1], 0.23, tight[2], tight[3], 0.23);
        VolSpreadStrategy.Params params = new VolSpreadStrategy.Params(0.01, 50.0, 7, 14, 1, "VRP", 0.65, false);

        FairVolProvider fairVol = (sym, exp, strk, t) -> OptionalDouble.of(0.18); // RV forecast is 18%, market is 23%

        VolSpreadStrategy strategy = new VolSpreadStrategy(testManager(tracker, transport), market, flatReferenceSurface(0.20),
                fairVol, null, () -> spot(100.0), tracker, "SPY", R, Q, params, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy.allowSimulatedChains(true);

        VolSpreadStrategy.Decision decision = strategy.step();
        assertEquals("SELL_STRADDLE", decision.action());
        assertEquals(0.18, decision.referenceIv(), 1e-4, "Reference IV should come from independent fair vol forecast");
        assertEquals(0.05, decision.edge(), 0.002, "Edge should reflect VRP against fair vol forecast (0.23 - 0.18)");
    }
}
