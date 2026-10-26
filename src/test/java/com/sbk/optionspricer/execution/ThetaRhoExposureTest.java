package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.FillRecorder;
import com.sbk.optionspricer.risk.PortfolioPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The book's net theta and rho are summed from the pricer's per-contract values, like delta, gamma and vega. */
class ThetaRhoExposureTest {

    @Test
    void netThetaAndRhoScaleWithQuantityAndMultiplierAndCarryTheirSign() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY261120C00780000", 2, 100, 9.90));
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY261120P00780000", -1, 100, 8.10));
        // the valuation service supplies these per contract: theta per year, rho per unit of rate
        tracker.getPosition("SPY261120C00780000").updateGreeks(0.52, 0.01, 30.0, -800.0, 40.0);
        tracker.getPosition("SPY261120P00780000").updateGreeks(-0.48, 0.01, 30.0, -700.0, -35.0);

        PositionTracker.PortfolioExposure exposure = tracker.snapshotExposure();

        assertEquals(2 * 100 * -800.0 + -1 * 100 * -700.0, exposure.netTheta(), 1e-9, "long 2 calls decay, the short put earns");
        assertEquals(2 * 100 * 40.0 + -1 * 100 * -35.0, exposure.netRho(), 1e-9);
        assertEquals(exposure.netTheta(), tracker.getNetTheta(), 1e-9);
        assertEquals(exposure.netRho(), tracker.getNetRho(), 1e-9);
    }

    @Test
    void sharesHaveNoThetaOrRhoAndAnUnvaluedContractContributesNothing() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 300, 1, 777.0));
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY261120C00780000", 1, 100, 9.90));

        PositionTracker.PortfolioExposure exposure = tracker.snapshotExposure();

        assertEquals(300.0, exposure.netDelta(), 1e-9, "the share is valued at delta one");
        assertEquals(0.0, exposure.netTheta(), 1e-9);
        assertEquals(0.0, exposure.netRho(), 1e-9);
    }

    @Test
    void theThreeGreekUpdateResetsThetaAndRhoAndTheFiveGreekUpdateRejectsNonFiniteValues() {
        PortfolioPosition position = new PortfolioPosition("SPY261120C00780000", 1, 100);
        position.updateGreeks(0.5, 0.01, 30.0, -800.0, 40.0);
        assertEquals(-80_000.0, position.getPositionTheta(), 1e-9);
        assertEquals(4_000.0, position.getPositionRho(), 1e-9);

        position.updateGreeks(0.5, 0.01, 30.0);
        assertEquals(0.0, position.getTheta(), 1e-9, "a caller that knows only three Greeks does not leave a stale theta behind");
        assertEquals(0.0, position.getRho(), 1e-9);
        assertTrue(position.isValued());

        assertThrows(IllegalArgumentException.class, () -> position.updateGreeks(0.5, 0.01, 30.0, Double.NaN, 40.0));
        assertThrows(IllegalArgumentException.class, () -> position.updateGreeks(0.5, 0.01, 30.0, -800.0, Double.POSITIVE_INFINITY));
    }

    @Test
    void theFourArgumentExposureIsStillAvailableWithZeroThetaAndRho() {
        PositionTracker.PortfolioExposure exposure = new PositionTracker.PortfolioExposure(1.0, 2.0, 3.0, 4.0);
        assertEquals(0.0, exposure.netTheta());
        assertEquals(0.0, exposure.netRho());
    }
}
