package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionType;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dupire local volatility checked three independent ways: a flat surface, a time-only surface with a
 * closed form, and finite differences of the call-price surface itself (the definition of Dupire's formula).
 */
class DupireLocalVolTest {

    private static final double S = 100.0;

    @Test
    void aFlatSurfaceHasLocalVolEqualToImpliedVolForAnyRatesAndDividends() {
        for (double r : new double[]{0.0, 0.03, 0.08}) {
            for (double q : new double[]{0.0, 0.02}) {
                for (double k : new double[]{70, 100, 130}) {
                    for (double t : new double[]{0.25, 1.0, 3.0}) {
                        OptionalDouble local = SlvApproximation.computeDupireLocalVol(S, k, t, r, q, 0.25, 0.0, 0.0, 0.0);
                        assertTrue(local.isPresent(), "r=" + r + " q=" + q + " k=" + k + " t=" + t);
                        assertEquals(0.25, local.getAsDouble(), 1e-12, "r=" + r + " q=" + q + " k=" + k + " t=" + t);
                    }
                }
            }
        }
    }

    @Test
    void aTimeOnlySurfaceGivesTheClosedFormForwardVariance() {
        // Implied vol sigma(T) = 0.20 + 0.10 T, no strike dependence. Total variance w = sigma^2 T, and
        // the local variance is dw/dT = sigma^2 + 2 sigma T sigma' for every strike, whatever r and q are.
        double r = 0.04;
        double q = 0.015;
        for (double t : new double[]{0.3, 1.0, 2.5}) {
            double sigma = 0.20 + 0.10 * t;
            double expected = Math.sqrt(sigma * sigma + 2.0 * sigma * t * 0.10);
            for (double k : new double[]{60, 100, 160}) {
                OptionalDouble local = SlvApproximation.computeDupireLocalVol(S, k, t, r, q, sigma, 0.10, 0.0, 0.0);
                assertEquals(expected, local.orElseThrow(), 1e-10, "t=" + t + " k=" + k);
            }
        }
    }

    // sigma(T,K) = 0.20 + 0.15 * ln(K/S)^2 + 0.02 * ln(K/S) + 0.03 * T : a smooth smile with a mild term slope
    private static double sigma(double t, double k) {
        double m = Math.log(k / S);
        return 0.20 + 0.15 * m * m + 0.02 * m + 0.03 * t;
    }

    private static double call(double t, double k, double r, double q) {
        return BlackScholesPricer.price(OptionType.CALL, S, k, t, r, sigma(t, k), q);
    }

    @Test
    void matchesDupiresFormulaAppliedToFiniteDifferencesOfThePriceSurface() {
        double r = 0.035;
        double q = 0.012;
        double h = 1e-3;
        for (double k : new double[]{85, 100, 118}) {
            for (double t : new double[]{0.4, 1.0, 1.8}) {
                // Dupire: sigma_loc^2 = (C_T + (r - q) K C_K + q C) / (0.5 K^2 C_KK), derivatives of the PRICE surface.
                double kh = k * h;
                double cT = (call(t + h, k, r, q) - call(t - h, k, r, q)) / (2 * h);
                double cK = (call(t, k + kh, r, q) - call(t, k - kh, r, q)) / (2 * kh);
                double cKK = (call(t, k + kh, r, q) - 2 * call(t, k, r, q) + call(t, k - kh, r, q)) / (kh * kh);
                double expected = Math.sqrt((cT + (r - q) * k * cK + q * call(t, k, r, q)) / (0.5 * k * k * cKK));

                // The same quantity from the implied-vol surface's own derivatives (analytic).
                double m = Math.log(k / S);
                double sigmaK = (2 * 0.15 * m + 0.02) / k;
                double sigmaKK = (2 * 0.15 - (2 * 0.15 * m + 0.02)) / (k * k);
                OptionalDouble local = SlvApproximation.computeDupireLocalVol(S, k, t, r, q, sigma(t, k), 0.03, sigmaK, sigmaKK);

                assertTrue(local.isPresent(), "k=" + k + " t=" + t);
                assertEquals(expected, local.getAsDouble(), 2e-5, "k=" + k + " t=" + t);
            }
        }
    }

    @Test
    void anArbitrageableSurfaceHasNoLocalVolRatherThanAFlooredOne() {
        // Strongly negative strike-curvature of the smile makes C_KK <= 0: a butterfly arbitrage.
        assertTrue(SlvApproximation.computeDupireLocalVol(S, 100.0, 1.0, 0.03, 0.0, 0.2, 0.0, 0.0, -50.0).isEmpty());
        // Implied variance falling faster than time passes makes the numerator negative: a calendar arbitrage.
        assertTrue(SlvApproximation.computeDupireLocalVol(S, 100.0, 1.0, 0.03, 0.0, 0.2, -1.0, 0.0, 0.0).isEmpty());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SlvApproximation.computeDupireLocalVol(-1, 100, 1, 0.03, 0, 0.2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SlvApproximation.computeDupireLocalVol(S, 0, 1, 0.03, 0, 0.2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SlvApproximation.computeDupireLocalVol(S, 100, 1, 0.03, 0, 0.0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SlvApproximation.computeDupireLocalVol(S, 100, 1, Double.NaN, 0, 0.2, 0, 0, 0));
    }

    @Test
    void atExpiryTheLocalVolIsTheImpliedVol() {
        assertEquals(0.2, SlvApproximation.computeDupireLocalVol(S, 100, 1e-9, 0.03, 0, 0.2, 0.5, 0.1, 0.1).orElseThrow(), 0.0);
    }
}
