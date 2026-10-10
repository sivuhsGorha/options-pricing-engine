package com.sbk.optionspricer.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stress test is a Taylor expansion over a grid of spot moves (-15%, 0, +15%) and volatility moves (-20, 0, +20
 * points); the margin is the worst loss on the grid. Every expected value below is worked by hand from
 * {@code dP = delta*dS + 0.5*gamma*dS^2 + vega*dVol}, not taken from the function.
 *
 * <p>Found on review (2026-10-10): the grid had only its four corners, so a book that loses on a pure volatility
 * move (long gamma, short vega) showed zero margin, because every corner gains from the spot move.
 */
class MarginApproximationTest {

    private static final double SPOT = 100.0;

    @Test
    void aFlatBookNeedsNoMargin() {
        assertEquals(0.0, MarginApproximation.calculateInitialMargin(0.0, 0.0, 0.0, SPOT), 1e-12);
    }

    @Test
    void aLongDeltaBookLosesOnTheSpotFall() {
        // 100 shares: dS = -15 gives -1500, the worst point; the vol axis does not matter with no vega.
        assertEquals(1_500.0, MarginApproximation.calculateInitialMargin(100.0, 0.0, 0.0, SPOT), 1e-9);
        // Short 100: the loss is on the rise.
        assertEquals(1_500.0, MarginApproximation.calculateInitialMargin(-100.0, 0.0, 0.0, SPOT), 1e-9);
    }

    @Test
    void aShortGammaBookLosesOnAnySpotMoveInEitherDirection() {
        // -1 gamma: 0.5 * -1 * 15^2 = -112.5 at both +-15%.
        assertEquals(112.5, MarginApproximation.calculateInitialMargin(0.0, -1.0, 0.0, SPOT), 1e-9);
    }

    @Test
    void aShortVegaBookLosesWhenVolatilityRises() {
        // -100 vega, +0.20: -20 on the vol axis alone.
        assertEquals(20.0, MarginApproximation.calculateInitialMargin(0.0, 0.0, -100.0, SPOT), 1e-9);
    }

    @Test
    void aLongGammaShortVegaBookStillLosesOnAPureVolatilityMove() {
        // Every spot corner gains: 0.5 * 1 * 225 = +112.5, less 20 on the vol move = +92.5. Only the (0, +0.20)
        // point loses, by 100 * 0.2 = 20. The four-corner grid reported 0 here.
        assertEquals(20.0, MarginApproximation.calculateInitialMargin(0.0, 1.0, -100.0, SPOT), 1e-9);
    }

    @Test
    void theWorstPointOfAMixedBookIsFoundOnTheFullGrid() {
        // delta 10, gamma -0.5, vega -50 at spot 100:
        //   (-15, +0.2): -150 - 56.25 - 10 = -216.25   <- worst
        //   (-15,  0.0): -150 - 56.25      = -206.25
        //   (+15, +0.2): +150 - 56.25 - 10 =  +83.75
        assertEquals(216.25, MarginApproximation.calculateInitialMargin(10.0, -0.5, -50.0, SPOT), 1e-9);
    }

    @Test
    void theMarginScalesWithTheSpotThatSetsTheShockSize() {
        // 100 shares at spot 200: dS = -30, loss 3000.
        assertEquals(3_000.0, MarginApproximation.calculateInitialMargin(100.0, 0.0, 0.0, 200.0), 1e-9);
    }

    @Test
    void aLongVegaBookLosesWhenVolatilityFalls() {
        // +100 vega, -0.20: -20, the mirror of the short-vega case.
        assertEquals(20.0, MarginApproximation.calculateInitialMargin(0.0, 0.0, 100.0, SPOT), 1e-9);
    }

    @Test
    void aBookThatCannotLoseOnTheGridNeedsNoMargin() {
        // Pure long gamma with no delta and no vega gains on every spot move and is flat on the vol axis.
        assertEquals(0.0, MarginApproximation.calculateInitialMargin(0.0, 1.0, 0.0, SPOT), 1e-9);
    }
}
