package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.PortfolioPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioRiskAdmissionTest {

    @Test
    void admitsOrdersWithinPortfolioLimitsAndBlocksBreaches() {
        PositionTracker tracker = new PositionTracker();
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 20, 100, 100.0));

        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(
                1_000_000.0,
                5_000.0,
                10_000.0,
                100_000.0,
                50_000.0
        );

        PortfolioPosition spy = tracker.getPosition("SPY");
        spy.updateGreeks(0.50, 0.04, 20.0);

        assertTrue(admission.canAdmitOrder("SPY", 10, 100.0, tracker), "small order should fit within portfolio limits");
        assertFalse(admission.canAdmitOrder("SPY", 500, 100.0, tracker), "large order should breach exposure limits");
    }

    @Test
    void blocksOrdersWhosePostTradeDeltaBreachesTheLimit() {
        PositionTracker tracker = new PositionTracker();
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 20, 100, 100.0));
        tracker.getPosition("SPY").updateGreeks(0.50, 0.0, 0.0);

        // Notional and position limits are loose so only delta can bind.
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(
                100_000_000.0, 5_000.0, 1_000_000.0, 1_000_000.0, 50_000.0);

        // Post-trade delta = (20 + 100) * 100 * 0.5 = 6000 > 5000, even though the order alone adds only 5000.
        assertFalse(admission.canAdmitOrder("SPY", 100, 100.0, tracker));
        // Post-trade delta = (20 + 40) * 100 * 0.5 = 3000 <= 5000.
        assertTrue(admission.canAdmitOrder("SPY", 40, 100.0, tracker));
    }

    @Test
    void shortOrdersThatReduceExposureAreAdmitted() {
        PositionTracker tracker = new PositionTracker();
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 20, 100, 100.0));
        tracker.getPosition("SPY").updateGreeks(0.50, 0.0, 0.0);

        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(
                100_000_000.0, 1_500.0, 1_000_000.0, 1_000_000.0, 50_000.0);

        // Current delta 1000 + order -10 -> 500: reduces risk, must be allowed.
        assertTrue(admission.canAdmitOrder("SPY", -10, 100.0, tracker));
        // Flipping to a large short breaches: (20 - 80) * 100 * 0.5 = -3000, |3000| > 1500.
        assertFalse(admission.canAdmitOrder("SPY", -80, 100.0, tracker));
    }
}
