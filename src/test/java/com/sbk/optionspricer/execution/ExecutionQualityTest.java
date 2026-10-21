package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What the orders actually cost against the quote they were decided on, and how often they fill at all. */
class ExecutionQualityTest {

    @Test
    void aBuyFilledAboveTheMidCostsTheDifferenceAndASellFilledBelowItDoes() {
        ExecutionQuality quality = new ExecutionQuality();

        quality.record("single", 10, 10, true, 100.00, 100.10, 100.05, 1);   // paid 5 cents over the mid
        quality.record("single", 10, 10, false, 100.00, 99.90, 99.96, 1);    // received 4 cents under the mid

        ExecutionQuality.Stats s = quality.snapshot().get("single");

        assertEquals(2, s.attempts());
        assertEquals(2, s.filled());
        assertEquals(4.5, s.meanSlippageVsMidBps(), 1e-9);  // (5 + 4) bps / 2
        assertEquals(5.0, s.worstSlippageVsMidBps(), 1e-9);
        assertEquals((0.05 + 0.04) * 10, s.totalSlippageDollars(), 1e-9);
    }

    @Test
    void aFillBetterThanTheMidIsNegativeSlippageAndMeasuredAgainstTheTouchToo() {
        ExecutionQuality quality = new ExecutionQuality();

        quality.record("single", 1, 1, true, 100.00, 100.10, 99.98, 1); // bought under the mid: price improvement

        ExecutionQuality.Stats s = quality.snapshot().get("single");
        assertEquals(-2.0, s.meanSlippageVsMidBps(), 1e-9);
        assertEquals((99.98 - 100.10) / 100.10 * 10_000.0, s.meanSlippageVsTouchBps(), 1e-9, "the touch was 100.10, so 12 cents better than the limit it would have paid");
    }

    @Test
    void anOrderThatNeverFilledCountsAsAnAttemptAndLowersTheFillRateWithoutTouchingSlippage() {
        ExecutionQuality quality = new ExecutionQuality();

        quality.record("multileg", 3, 3, true, 10.00, 10.20, 10.10, 100);
        quality.record("multileg", 3, 0, true, 10.00, 10.20, Double.NaN, 100);
        quality.record("multileg", 3, 1, true, 10.00, 10.20, 10.15, 100);

        ExecutionQuality.Stats s = quality.snapshot().get("multileg");

        assertEquals(3, s.attempts());
        assertEquals(2, s.filled());
        assertEquals(2.0 / 3.0, s.fillRate(), 1e-9);
        assertEquals(4.0 / 9.0, s.quantityFillRatio(), 1e-9, "four of the nine contracts asked for");
        assertEquals(125.0, s.meanSlippageVsMidBps(), 1e-9, "(100 + 150) / 2 bps over the two that filled");
    }

    @Test
    void nothingRecordedMeansNothingReportedAndBadNumbersAreIgnored() {
        ExecutionQuality quality = new ExecutionQuality();
        assertTrue(quality.snapshot().isEmpty());

        quality.record("single", 0, 0, true, 100, 100, 100, 1);       // asked for nothing
        quality.record("single", 5, 5, true, 0.0, 100, 100, 1);       // no usable mid: the fill counts, the slippage cannot be judged
        ExecutionQuality.Stats s = quality.snapshot().get("single");

        assertEquals(1, s.attempts());
        assertEquals(1, s.filled());
        assertTrue(Double.isNaN(s.meanSlippageVsMidBps()));
    }

    @Test
    void theOrderManagerRecordsASingleOrderAgainstItsSnapshot() {
        ExecutionQuality quality = new ExecutionQuality();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1_000, 1e9, 1_000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), ask + 0.02, true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.allowSimulated(), new TradingHalt(), null, 1);
        manager.setExecutionQuality(quality);
        MarketSnapshot snapshot = new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 10, 100.01), snapshot);

        assertTrue(decision.accepted(), decision.message());
        ExecutionQuality.Stats s = quality.snapshot().get("single");
        assertEquals(1, s.attempts());
        assertEquals(1, s.filled());
        assertEquals(3.0, s.meanSlippageVsMidBps(), 1e-6, "filled 100.03 against a 100.00 mid");
    }

    @Test
    void anOrderRejectedByAGateNeverReachedTheVenueAndIsNotAnAttempt() {
        ExecutionQuality quality = new ExecutionQuality();
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1, 1e9, 1),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), ask, true, "Executed"),
                new PositionTracker(FillRecorder.NONE), OrderManager.MarketDataPolicy.allowSimulated(), new TradingHalt(), null, 1);
        manager.setExecutionQuality(quality);
        MarketSnapshot snapshot = new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 500, 100.01), snapshot);

        assertFalse(decision.accepted());
        assertTrue(quality.snapshot().isEmpty(), "a refusal by our own limits says nothing about execution quality");
    }

    @Test
    void theJsonHasPlainValuesPerKind() {
        ExecutionQuality quality = new ExecutionQuality();
        quality.record("single", 2, 2, true, 50.0, 50.1, 50.05, 1);

        Map<String, Object> json = quality.toJson();

        @SuppressWarnings("unchecked")
        Map<String, Object> single = (Map<String, Object>) json.get("single");
        assertEquals(1L, single.get("attempts"));
        assertEquals(1.0, (Double) single.get("fillRate"), 1e-9);
        assertTrue(single.containsKey("meanSlippageVsMidBps") && single.containsKey("totalSlippageDollars"));
        assertEquals(List.of("single"), List.copyOf(json.keySet()));
    }
}
