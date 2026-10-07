package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SabrModelTest {

    private static final double ALPHA = 0.30;
    private static final double BETA = 0.6;
    private static final double RHO = -0.65;
    private static final double NU = 0.60;

    @Test
    void theSmileIsContinuousAcrossTheAtTheMoneyBoundaryToRoundingError() {
        // The second difference of a smooth function is O(delta^2) ~ 1e-17 here; a cancellation error in the
        // expansion near the money shows up as noise orders of magnitude larger.
        for (double f : new double[]{100.0, 3.0, 0.0125}) {
            double delta = 3e-9;
            double atm = SabrModel.impliedVolatility(f, f, 0.75, ALPHA, BETA, RHO, NU);
            double up = SabrModel.impliedVolatility(f, f * Math.exp(delta), 0.75, ALPHA, BETA, RHO, NU);
            double down = SabrModel.impliedVolatility(f, f * Math.exp(-delta), 0.75, ALPHA, BETA, RHO, NU);
            assertEquals(0.0, up + down - 2.0 * atm, 1e-12, "forward " + f);
        }
    }

    @Test
    void theAtTheMoneyTestIsRelativeSoLowPricedForwardsAreNotMisclassified() {
        // A forward of 1e-4 (e.g. a rate in decimals): a strike 0.05% away is only 5e-8 away in absolute terms,
        // which an absolute 1e-7 threshold would wrongly treat as exactly at the money.
        double f = 1e-4;
        double atm = SabrModel.impliedVolatility(f, f, 1.0, 0.005, 0.5, -0.4, 0.5);
        double off = SabrModel.impliedVolatility(f, f * 1.0005, 1.0, 0.005, 0.5, -0.4, 0.5);

        assertNotEquals(atm, off, 1e-9 * atm, "the skew must show up: " + atm + " vs " + off);
    }

    @Test
    void theLognormalLimitIsFlatAtAlpha() {
        // beta = 1 and no vol-of-vol is Black-Scholes with volatility alpha: the smile is flat.
        for (double k : new double[]{60, 80, 100, 130, 200}) {
            assertEquals(0.2, SabrModel.impliedVolatility(100, k, 1.0, 0.2, 1.0, 0.0, 1e-9), 1e-8, "k=" + k);
        }
    }

    @Test
    void negativeCorrelationSkewsTheSmileDownwardsAndVolOfVolAddsCurvature() {
        double f = 100;
        double low = SabrModel.impliedVolatility(f, 90, 1.0, ALPHA, 1.0, -0.6, 0.5);
        double mid = SabrModel.impliedVolatility(f, 100, 1.0, ALPHA, 1.0, -0.6, 0.5);
        double high = SabrModel.impliedVolatility(f, 110, 1.0, ALPHA, 1.0, -0.6, 0.5);
        assertTrue(low > mid && mid > high, "negative rho: vol falls with strike: " + low + " " + mid + " " + high);

        double symLow = SabrModel.impliedVolatility(f, 80, 1.0, ALPHA, 1.0, 0.0, 0.8);
        double symMid = SabrModel.impliedVolatility(f, 100, 1.0, ALPHA, 1.0, 0.0, 0.8);
        double symHigh = SabrModel.impliedVolatility(f, 125, 1.0, ALPHA, 1.0, 0.0, 0.8);
        assertTrue(symLow > symMid && symHigh > symMid, "rho = 0, nu > 0: a smile");
    }

    @Test
    void correlationNearOneStaysFinite() {
        double v = SabrModel.impliedVolatility(100, 120, 1.0, ALPHA, BETA, 0.999999, NU);
        assertTrue(Double.isFinite(v) && v > 0.0, "vol " + v);
    }

    @Test
    void invalidParametersAreRejectedNotReturnedAsAZeroVolatility() {
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(0, 100, 1, ALPHA, BETA, RHO, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, -1, 1, ALPHA, BETA, RHO, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, 100, 0, ALPHA, BETA, RHO, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, 100, 1, 0.0, BETA, RHO, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, 100, 1, ALPHA, 1.5, RHO, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, 100, 1, ALPHA, -0.1, RHO, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, 100, 1, ALPHA, BETA, 1.0, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, 100, 1, ALPHA, BETA, -1.0, NU));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(100, 100, 1, ALPHA, BETA, RHO, -0.1));
        assertThrows(IllegalArgumentException.class, () -> SabrModel.impliedVolatility(Double.NaN, 100, 1, ALPHA, BETA, RHO, NU));
    }

    @Test
    void theFreeBoundaryVariantIsNotAnIndependentModel() {
        // It claimed a density correction that is not implemented (and clamped z, distorting the wings with a 1e-6 fast log).
        // It is now the Hagan formula under another name, and must agree with it exactly, wings included.
        for (double k : new double[]{40, 80, 100, 140, 300}) {
            assertEquals(SabrModel.impliedVolatility(100, k, 0.5, ALPHA, BETA, RHO, NU),
                    SabrFreeBoundaryModel.impliedVolatility(100, k, 0.5, ALPHA, BETA, RHO, NU), 0.0, "k=" + k);
        }
    }
}
