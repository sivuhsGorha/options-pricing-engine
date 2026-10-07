package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Prices cross the wire as exact decimal ticks, and one contract multiplier drives every notional and exposure. */
class MoneyAndMultiplierTest {

    // ---------------- price ticks ----------------

    @Test
    void pricesRoundToTheNearestTickInsteadOfTruncating() {
        // (long)(1.6 * 10000) is 15999 because 1.6 is not exactly representable; the wire must carry 16000.
        assertEquals(16_000L, PriceScale.toTicks(1.6));
        assertEquals(1_234_567L, PriceScale.toTicks(123.4567));
        assertEquals(1_000_000L, PriceScale.toTicks(100.0));
        assertEquals(1L, PriceScale.toTicks(0.0001));
        assertEquals(0L, PriceScale.toTicks(0.0));
        // Exactly half a tick rounds to even in decimal terms (the shortest decimal form of the double is used).
        assertEquals(10_000L, PriceScale.toTicks(1.00005));
        assertEquals(10_002L, PriceScale.toTicks(1.00015));
    }

    @Test
    void everyDecimalPriceOnTheTickGridSurvivesARoundTrip() {
        for (long ticks = 1; ticks <= 2_000_000; ticks += 7) {
            double price = ticks / 10_000.0;
            assertEquals(ticks, PriceScale.toTicks(price), "price " + price);
            assertEquals(price, PriceScale.fromTicks(ticks), 1e-12);
        }
    }

    @Test
    void impossiblePricesAreRejectedAtTheBoundary() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1e300}) {
            assertThrows(IllegalArgumentException.class, () -> PriceScale.toTicks(bad), "price " + bad);
        }
    }

    // ---------------- multiplier ----------------

    private static MarketSnapshot live() {
        return new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static OrderManager manager(PositionTracker tracker, PreTradeRiskFilter filter, int multiplier, PortfolioRiskAdmission admission) {
        return new OrderManager(filter,
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.strict(), new TradingHalt(), admission, multiplier);
    }

    @Test
    void theContractMultiplierScalesNotionalLimitsAndPositions() {
        // 10 contracts at 100.0: notional 1,000 with multiplier 1, but 100,000 with multiplier 100.
        PreTradeRiskFilter filter = new PreTradeRiskFilter(1_000, 50_000.0, 1_000);

        PositionTracker single = new PositionTracker(FillRecorder.NONE);
        assertTrue(manager(single, filter, 1, null).submit(new Order(1, true, 10, 100.0), live()).accepted());
        assertEquals(10.0, single.getNetDelta(), 1e-9);

        PositionTracker hundred = new PositionTracker(FillRecorder.NONE);
        OrderManager.OrderDecision blocked = manager(hundred, new PreTradeRiskFilter(1_000, 50_000.0, 1_000), 100, null)
                .submit(new Order(1, true, 10, 100.0), live());
        assertFalse(blocked.accepted(), "notional 10 x 100.0 x 100 = 100,000 exceeds the 50,000 order limit");
        assertEquals(0, hundred.getNetQuantity("SPY"));
    }

    @Test
    void positionsAndExposureUseTheSameMultiplierAsTheOrderNotional() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = manager(tracker, new PreTradeRiskFilter(1_000, 1e9, 1_000), 100, null);

        assertTrue(manager.submit(new Order(1, true, 3, 100.0), live()).accepted());

        assertEquals(100, tracker.getPosition("SPY").getMultiplier());
        assertEquals(300.0, tracker.getNetDelta(), 1e-9, "3 contracts x 100 multiplier x delta 1");
        assertEquals(30_000.0, tracker.getNetNotional(), 1e-6);
    }

    @Test
    void concentrationLimitsAccumulateNotionalIncludingTheMultiplier() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(1_000, 1e9, 1_000, Map.of(1, "SPY"),
                new ConcentrationLimitManager(Map.of("SPY", 50_000.0)), null);
        OrderManager manager = manager(new PositionTracker(FillRecorder.NONE), filter, 100, null);

        assertTrue(manager.submit(new Order(1, true, 3, 100.0), live()).accepted());   // exposure 30,000
        assertFalse(manager.submit(new Order(1, true, 3, 100.0), live()).accepted(),  // would reach 60,000 > 50,000
                "the second order must be blocked: concentration counts quantity x price x multiplier");
    }

    @Test
    void portfolioAdmissionSeesTheSameMultiplierForANewSymbol() {
        // Delta limit 500: one contract of a x100 instrument is 100 delta, the sixth is 600.
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1e12, 500.0, 1e12, 1e12, 1e12);
        OrderManager manager = manager(new PositionTracker(FillRecorder.NONE),
                new PreTradeRiskFilter(1_000, 1e12, 1_000), 100, admission);

        for (int i = 0; i < 5; i++) {
            assertTrue(manager.submit(new Order(1, true, 1, 100.0), live()).accepted(), "order " + (i + 1));
        }
        OrderManager.OrderDecision sixth = manager.submit(new Order(1, true, 1, 100.0), live());
        assertFalse(sixth.accepted());
        assertTrue(sixth.message().contains("portfolio"), sixth.message());
    }

    @Test
    void invalidMultipliersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager(new PositionTracker(FillRecorder.NONE), new PreTradeRiskFilter(10, 1e9, 10), 0, null));
        assertThrows(IllegalArgumentException.class, () -> manager(new PositionTracker(FillRecorder.NONE), new PreTradeRiskFilter(10, 1e9, 10), -5, null));
    }
}
