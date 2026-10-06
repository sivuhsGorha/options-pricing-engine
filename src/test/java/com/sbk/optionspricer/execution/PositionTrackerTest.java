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

        PositionTracker.PortfolioExposure exposure = PositionTracker.snapshotPortfolioExposure();
        assertTrue(exposure.netDelta() != 0.0 || exposure.netGamma() != 0.0 || exposure.netVega() != 0.0,
                "accepted fills should produce a non-zero tracked exposure snapshot");
        assertTrue(exposure.netNotional() > 0.0, "tracked notional should remain positive after fills");
    }
}
