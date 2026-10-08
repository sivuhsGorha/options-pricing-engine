package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Average-cost accounting: adds blend, reductions realise, flips reopen at the fill price. */
class AverageCostTest {

    private static void fill(PositionTracker t, int qty, double price) {
        t.applyFill(new PositionTracker.ExecutionFill("SPY", qty, 1, price));
    }

    @Test
    void addsBlendIntoTheAverageAndReductionsRealiseAgainstIt() {
        PositionTracker t = new PositionTracker(FillRecorder.NONE);
        assertTrue(Double.isNaN(t.getAverageCost("SPY")), "flat: no average cost");

        fill(t, 10, 100.0);
        fill(t, 10, 110.0);
        assertEquals(105.0, t.getAverageCost("SPY"), 1e-9);
        assertEquals(0.0, t.getRealizedPnl(), 1e-9, "nothing closed yet");

        fill(t, -5, 120.0);
        assertEquals(75.0, t.getRealizedPnl(), 1e-9, "5 x (120 - 105)");
        assertEquals(105.0, t.getAverageCost("SPY"), 1e-9, "a reduction leaves the average alone");
        assertEquals(15, t.getNetQuantity("SPY"));
    }

    @Test
    void aFlipClosesTheOldSideAndOpensTheNewOneAtTheFillPrice() {
        PositionTracker t = new PositionTracker(FillRecorder.NONE);
        fill(t, 10, 100.0);

        fill(t, -15, 90.0);

        assertEquals(-100.0, t.getRealizedPnl(), 1e-9, "10 closed x (90 - 100)");
        assertEquals(-5, t.getNetQuantity("SPY"));
        assertEquals(90.0, t.getAverageCost("SPY"), 1e-9, "the short 5 opened at 90");

        fill(t, 5, 80.0);
        assertEquals(-100.0 + 5 * (90.0 - 80.0), t.getRealizedPnl(), 1e-9, "covering a short below its cost is a gain");
        assertEquals(0, t.getNetQuantity("SPY"));
        assertTrue(Double.isNaN(t.getAverageCost("SPY")), "flat again");
    }

    @Test
    void theMultiplierScalesRealisedProfit() {
        PositionTracker t = new PositionTracker(FillRecorder.NONE);
        t.applyFill(new PositionTracker.ExecutionFill("SPY261120C00780000", 2, 100, 9.90));
        t.applyFill(new PositionTracker.ExecutionFill("SPY261120C00780000", -2, 100, 10.40));

        assertEquals(2 * 100 * 0.50, t.getRealizedPnl(), 1e-9);
    }
}
