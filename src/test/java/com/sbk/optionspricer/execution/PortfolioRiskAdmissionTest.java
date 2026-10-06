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
                2_000.0,
                50_000.0
        );

        PortfolioPosition spy = tracker.getPosition("SPY");
        spy.updateGreeks(0.50, 0.04, 20.0);

        assertTrue(admission.canAdmitOrder("SPY", 10, 100.0, tracker), "small order should fit within portfolio limits");
        assertFalse(admission.canAdmitOrder("SPY", 500, 100.0, tracker), "large order should breach exposure limits");
    }
}
