package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SsviSviRigorTest {

    // ---------------- SSVI ----------------

    @Test
    void staticNoArbitrageConditionsAreEvaluatedFromTheTheory() {
        // Gatheral-Jacquier: theta*phi*(1+|rho|) < 4 and theta*phi^2*(1+|rho|) <= 4 for all theta > 0.
        assertTrue(new SsviApproximation.SsviParams(1.0, 0.25, -0.5).satisfiesStaticNoArbitrageConditions());
        assertTrue(new SsviApproximation.SsviParams(0.55, 0.25, -0.5).satisfiesStaticNoArbitrageConditions());
        assertFalse(new SsviApproximation.SsviParams(5.0, 0.49, -0.99).satisfiesStaticNoArbitrageConditions(), "eta (1 + |rho|) > 4");
        // eta (1 + |rho|) = 3 passes the first condition; theta phi^2 (1 + |rho|) peaks at eta^2 (1 + |rho|) = 6 > 4.
        assertFalse(new SsviApproximation.SsviParams(2.0, 0.5, 0.5).satisfiesStaticNoArbitrageConditions(), "second condition fails");
    }

    @Test
    void parametersThatSatisfyTheConditionsHaveNoButterflyArbitrageOnADenseGrid() {
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(1.2, 0.3, -0.6);
        assertTrue(params.satisfiesStaticNoArbitrageConditions());
        for (double theta : new double[]{0.001, 0.01, 0.05, 0.2, 0.5, 1.0, 2.0, 5.0}) {
            for (double k = -3.0; k <= 3.0; k += 0.01) {
                assertTrue(SsviApproximation.isArbitrageFree(k, theta, params), "theta=" + theta + " k=" + k);
            }
        }
    }

    @Test
    void parametersThatViolateTheConditionsDoShowButterflyArbitrageSomewhere() {
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(5.0, 0.49, -0.99);
        boolean found = false;
        for (double theta : new double[]{0.04, 0.5, 1.0, 2.0, 4.0}) {
            for (double k = -3.0; k <= 3.0 && !found; k += 0.01) {
                found = !SsviApproximation.isArbitrageFree(k, theta, params);
            }
        }
        assertTrue(found, "the necessary side of the check must fire for clearly bad parameters");
    }

    @Test
    void totalVarianceIsNondecreasingInMaturityAtEveryStrike() {
        // No calendar-spread arbitrage: w(k, theta2) >= w(k, theta1) for theta2 > theta1.
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(1.2, 0.3, -0.6);
        for (double k = -3.0; k <= 3.0; k += 0.05) {
            double previous = 0.0;
            for (double theta = 0.005; theta <= 6.0; theta *= 1.15) {
                double w = SsviApproximation.totalVariance(k, theta, params);
                assertTrue(w >= previous - 1e-12, "k=" + k + " theta=" + theta);
                previous = w;
            }
        }
    }

    @Test
    void atTheForwardTheSurfaceReturnsTheAtTheMoneyVolatilityExactly() {
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(1.0, 0.25, -0.5);
        double forward = 100.0 * Math.exp((0.04 - 0.01) * 2.0);

        assertEquals(0.22, SsviApproximation.impliedVolFromForward(forward, forward, 2.0, 0.22, params), 1e-13);
    }

    @Test
    void theForwardMattersWhenRatesAndDividendsDiffer() {
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(1.0, 0.25, -0.5);
        double spot = 100.0;
        double forward = spot * Math.exp(0.05 * 2.0);
        double fromSpotMoneyness = SsviApproximation.impliedVol(spot, 105.0, 2.0, 0.22, params);
        double fromForward = SsviApproximation.impliedVolFromForward(forward, 105.0, 2.0, 0.22, params);

        assertNotEquals(fromSpotMoneyness, fromForward, 1e-4, "log-moneyness against the forward, not the spot");
    }

    @Test
    void invalidInputsAreRejectedRatherThanReturnedAsAZeroOrFlooredVolatility() {
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(1.0, 0.25, -0.5);
        assertThrows(IllegalArgumentException.class, () -> SsviApproximation.impliedVolFromForward(0, 100, 1, 0.2, params));
        assertThrows(IllegalArgumentException.class, () -> SsviApproximation.impliedVolFromForward(100, -1, 1, 0.2, params));
        assertThrows(IllegalArgumentException.class, () -> SsviApproximation.impliedVolFromForward(100, 100, 0, 0.2, params));
        assertThrows(IllegalArgumentException.class, () -> SsviApproximation.impliedVolFromForward(100, 100, 1, 0.0, params));
        assertThrows(IllegalArgumentException.class, () -> SsviApproximation.impliedVolFromForward(100, 100, 1, Double.NaN, params));
    }

    // ---------------- raw SVI ----------------

    @Test
    void sviRejectsParametersThatCanProduceNegativeVariance() {
        assertThrows(IllegalArgumentException.class, () -> SviModel.impliedVariance(0.0, 0.04, -0.1, 0.0, 0.0, 0.1), "b < 0");
        assertThrows(IllegalArgumentException.class, () -> SviModel.impliedVariance(0.0, 0.04, 0.1, 1.0, 0.0, 0.1), "|rho| = 1");
        assertThrows(IllegalArgumentException.class, () -> SviModel.impliedVariance(0.0, 0.04, 0.1, 0.0, 0.0, 0.0), "sigma = 0");
        // a + b*sigma*sqrt(1-rho^2) < 0 puts the smile minimum below zero variance.
        assertThrows(IllegalArgumentException.class, () -> SviModel.impliedVariance(0.0, -0.5, 0.1, 0.0, 0.0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> SviModel.impliedVariance(Double.NaN, 0.04, 0.1, 0.0, 0.0, 0.1));
    }

    @Test
    void sviValuesFollowTheRawFormula() {
        double a = 0.04, b = 0.4, rho = -0.4, m = 0.1, sigma = 0.2, k = 0.35;
        double expected = a + b * (rho * (k - m) + Math.sqrt((k - m) * (k - m) + sigma * sigma));
        assertEquals(expected, SviModel.impliedVariance(k, a, b, rho, m, sigma), 1e-15);
        assertTrue(SviModel.impliedVariance(m, a, b, rho, m, sigma) > 0.0);
    }

    @Test
    void sviVolatilityConversionRejectsImpossibleInputs() {
        assertEquals(0.2, SviModel.impliedVolatility(0.04, 1.0), 1e-15);
        assertEquals(0.0, SviModel.impliedVolatility(0.0, 1.0), 0.0, "zero variance is zero volatility");
        assertThrows(IllegalArgumentException.class, () -> SviModel.impliedVolatility(-0.01, 1.0));
        assertThrows(IllegalArgumentException.class, () -> SviModel.impliedVolatility(0.04, 0.0));
    }
}
