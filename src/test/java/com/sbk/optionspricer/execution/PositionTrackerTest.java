package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.PortfolioPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionTrackerTest {

    @Test
    void appliesFillsToPortfolioPositionsAndReconcilesNetQuantity() {
        PositionTracker tracker = new PositionTracker();

        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 100, 101.00));
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", -4, 100, 102.50));

        PortfolioPosition position = tracker.getPosition("SPY");
        assertNotNull(position);
        assertEquals(6, position.getQuantity());
        assertEquals(6, tracker.getNetQuantity("SPY"));
        assertTrue(tracker.getNotional("SPY") > 0.0, "notional should remain positive after fills");
    }

    @Test
    void positionDeltaIsLinearInQuantityAndKeepsItsSign() {
        PositionTracker longTracker = new PositionTracker();
        longTracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 100, 100.0));
        assertEquals(1_000.0, longTracker.getNetDelta(), 1e-9, "long 10 x 100 shares = +1000 delta");

        longTracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 100, 100.0));
        assertEquals(2_000.0, longTracker.getNetDelta(), 1e-9, "delta must scale linearly, not quadratically");

        PositionTracker shortTracker = new PositionTracker();
        shortTracker.applyFill(new PositionTracker.ExecutionFill("SPY", -10, 100, 100.0));
        assertEquals(-1_000.0, shortTracker.getNetDelta(), 1e-9, "short 10 x 100 shares = -1000 delta");
    }

    @Test
    void fillsDoNotOverwritePricerSuppliedGreeks() {
        PositionTracker tracker = new PositionTracker();
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 100, 100.0));
        tracker.getPosition("SPY").updateGreeks(0.5, 0.04, 20.0);

        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 100, 100.0));

        assertEquals(0.5, tracker.getPosition("SPY").getDelta(), 1e-12);
        assertEquals(0.04, tracker.getPosition("SPY").getGamma(), 1e-12);
        assertEquals(20.0, tracker.getPosition("SPY").getVega(), 1e-12);
        assertEquals(20 * 100 * 0.5, tracker.getNetDelta(), 1e-9);
    }

    @Test
    void rejectsInvalidFillData() {
        PositionTracker tracker = new PositionTracker();
        assertThrows(IllegalArgumentException.class, () -> tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 0, 100, 101.0)));
        assertThrows(IllegalArgumentException.class, () -> tracker.applyFill(new PositionTracker.ExecutionFill("", 5, 100, 101.0)));
        assertThrows(IllegalArgumentException.class, () -> tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 5, 0, 101.0)));
    }

    @Test
    void aggregatesTrackedExposureAfterAcceptedFills() {
        PositionTracker tracker = new PositionTracker();
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 100, 101.0));
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", -4, 100, 102.5));

        PositionTracker.PortfolioExposure exposure = tracker.snapshotExposure();
        assertTrue(exposure.netDelta() != 0.0 || exposure.netGamma() != 0.0 || exposure.netVega() != 0.0,
                "accepted fills should produce a non-zero tracked exposure snapshot");
        assertTrue(exposure.netNotional() > 0.0, "tracked notional should remain positive after fills");
    }
}
