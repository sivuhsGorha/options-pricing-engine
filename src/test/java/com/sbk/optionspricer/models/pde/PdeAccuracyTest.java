package com.sbk.optionspricer.models.pde;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.models.pde.DiscreteDividendPricer.DiscreteDividend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The finite-difference solver checked against independent references: closed-form Black-Scholes, a
 * high-resolution binomial tree (American, with a dividend yield), and the identity that a cash dividend
 * paid an instant before expiry is a shift of the strike.
 */
class PdeAccuracyTest {

    private static double pde(OptionType type, double s, double k, double t, double r, double q, double vol,
                              DiscreteDividend[] dividends, int n, int m, boolean american) {
        return DiscreteDividendPricer.price(type, new OptionParameters(s, k, t, r, vol, q), dividends, n, m, american);
    }

    /** Cox-Ross-Rubinstein American price with a continuous dividend yield (independent of the PDE code). */
    private static double binomialAmerican(OptionType type, double s, double k, double t, double r, double q, double vol, int steps) {
        double dt = t / steps;
        double u = Math.exp(vol * Math.sqrt(dt));
        double d = 1.0 / u;
        double p = (Math.exp((r - q) * dt) - d) / (u - d);
        double disc = Math.exp(-r * dt);
        double[] v = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double spot = s * Math.pow(u, steps - i) * Math.pow(d, i);
            v[i] = type == OptionType.CALL ? Math.max(spot - k, 0.0) : Math.max(k - spot, 0.0);
        }
        for (int step = steps - 1; step >= 0; step--) {
            for (int i = 0; i <= step; i++) {
                double spot = s * Math.pow(u, step - i) * Math.pow(d, i);
                double cont = disc * (p * v[i] + (1 - p) * v[i + 1]);
                double intrinsic = type == OptionType.CALL ? Math.max(spot - k, 0.0) : Math.max(k - spot, 0.0);
                v[i] = Math.max(cont, intrinsic);
            }
        }
        return v[0];
    }

    // ---------------- European ----------------

    @Test
    void europeanPricesMatchBlackScholesIncludingTheDividendYield() {
        double[][] cases = {
                // s, k, t, r, q, vol
                {100, 100, 1.0, 0.05, 0.00, 0.20},
                {100, 100, 1.0, 0.05, 0.04, 0.20},   // the dividend yield must enter the drift
                {100, 80, 0.5, 0.03, 0.02, 0.35},
                {100, 125, 2.0, 0.04, 0.01, 0.25},
                {50, 52, 0.25, 0.01, 0.00, 0.60},
        };
        for (double[] c : cases) {
            for (OptionType type : OptionType.values()) {
                double expected = BlackScholesPricer.price(type, c[0], c[1], c[2], c[3], c[5], c[4]);
                double actual = pde(type, c[0], c[1], c[2], c[3], c[4], c[5], null, 400, 400, false);
                assertEquals(expected, actual, 6e-4 * Math.max(1.0, expected), type + " " + java.util.Arrays.toString(c));
            }
        }
    }

    @Test
    void errorShrinksAboutFourfoldWhenTheGridIsRefinedTwofold() {
        double expected = BlackScholesPricer.price(OptionType.CALL, 100.0, 105.0, 1.0, 0.03, 0.25, 0.01);
        double e1 = Math.abs(pde(OptionType.CALL, 100, 105, 1.0, 0.03, 0.01, 0.25, null, 100, 100, false) - expected);
        double e2 = Math.abs(pde(OptionType.CALL, 100, 105, 1.0, 0.03, 0.01, 0.25, null, 200, 200, false) - expected);
        double e3 = Math.abs(pde(OptionType.CALL, 100, 105, 1.0, 0.03, 0.01, 0.25, null, 400, 400, false) - expected);

        assertTrue(e2 < e1 / 2.8, "100->200: " + e1 + " -> " + e2);
        assertTrue(e3 < e2 / 2.8, "200->400: " + e2 + " -> " + e3);
    }

    @Test
    void strikesFarOutsideTheTypicalGridStillPriceCorrectlyAndNeverNegative() {
        // sigma*sqrt(T) = 0.032: a +/-4.5 sd grid around the spot ends near 115, well short of these strikes.
        for (double k : new double[]{70, 90, 110, 130, 160}) {
            for (OptionType type : OptionType.values()) {
                double expected = BlackScholesPricer.price(type, 100.0, k, 0.1, 0.03, 0.10, 0.0);
                double actual = pde(type, 100, k, 0.1, 0.03, 0.0, 0.10, null, 300, 300, false);
                assertTrue(actual >= 0.0 && Double.isFinite(actual), type + " k=" + k + " price=" + actual);
                assertEquals(expected, actual, 2e-4, type + " k=" + k);
            }
        }
    }

    // ---------------- American ----------------

    @Test
    void americanPutsMatchAHighResolutionTree() {
        double[][] cases = {
                // s, k, t, r, q, vol
                {100, 100, 1.0, 0.05, 0.0, 0.20},
                {100, 110, 0.5, 0.08, 0.0, 0.30},
                {100, 130, 1.0, 0.06, 0.0, 0.25},    // deep in the money: exercise region is large
                {100, 90, 2.0, 0.04, 0.02, 0.35},
        };
        for (double[] c : cases) {
            double tree = binomialAmerican(OptionType.PUT, c[0], c[1], c[2], c[3], c[4], c[5], 4000);
            double actual = pde(OptionType.PUT, c[0], c[1], c[2], c[3], c[4], c[5], null, 400, 400, true);
            assertEquals(tree, actual, 2.5e-3, java.util.Arrays.toString(c));
        }
    }

    @Test
    void americanCallsOnADividendYieldMatchAHighResolutionTree() {
        // With q > r early exercise of a call is optimal at high spot: the constraint binds at the other end of the grid.
        double[][] cases = {
                {100, 100, 1.0, 0.03, 0.07, 0.25},
                {100, 90, 1.5, 0.02, 0.06, 0.30},
        };
        for (double[] c : cases) {
            double tree = binomialAmerican(OptionType.CALL, c[0], c[1], c[2], c[3], c[4], c[5], 4000);
            double actual = pde(OptionType.CALL, c[0], c[1], c[2], c[3], c[4], c[5], null, 400, 400, true);
            assertEquals(tree, actual, 2.5e-3, java.util.Arrays.toString(c));
        }
    }

    @Test
    void americanIsAtLeastEuropeanAndAtLeastIntrinsic() {
        for (OptionType type : OptionType.values()) {
            double european = pde(type, 100, 105, 1.0, 0.05, 0.02, 0.3, null, 200, 200, false);
            double american = pde(type, 100, 105, 1.0, 0.05, 0.02, 0.3, null, 200, 200, true);
            double intrinsic = type == OptionType.CALL ? Math.max(100 - 105, 0) : Math.max(105 - 100, 0);
            assertTrue(american >= european - 1e-9, type + " american " + american + " < european " + european);
            assertTrue(american >= intrinsic - 1e-9, type + " american " + american + " < intrinsic");
        }
    }

    // ---------------- discrete cash dividends ----------------

    @Test
    void aCashDividendAnInstantBeforeExpiryShiftsTheStrike() {
        // Payoff max(S_T - K, 0) with S_T ~ S_{T-eps} - D equals a call struck at K + D.
        double d = 2.0;
        double t = 0.5;
        DiscreteDividend[] dividends = {new DiscreteDividend(t - 1e-3, d)};
        double expected = BlackScholesPricer.price(OptionType.CALL, 100.0, 100.0 + d, t, 0.04, 0.25, 0.0);
        double actual = pde(OptionType.CALL, 100, 100, t, 0.04, 0.0, 0.25, dividends, 400, 400, false);

        assertEquals(expected, actual, 4e-3);
    }

    @Test
    void aDividendAtOrAfterExpiryHasNoEffect() {
        double none = pde(OptionType.CALL, 100, 100, 1.0, 0.04, 0.0, 0.2, null, 200, 200, false);
        double atExpiry = pde(OptionType.CALL, 100, 100, 1.0, 0.04, 0.0, 0.2, new DiscreteDividend[]{new DiscreteDividend(1.0, 3.0)}, 200, 200, false);
        double afterExpiry = pde(OptionType.CALL, 100, 100, 1.0, 0.04, 0.0, 0.2, new DiscreteDividend[]{new DiscreteDividend(1.5, 3.0)}, 200, 200, false);

        assertEquals(none, atExpiry, 1e-12);
        assertEquals(none, afterExpiry, 1e-12);
    }

    @Test
    void dividendsMoveCallsDownAndPutsUp() {
        DiscreteDividend[] dividends = {new DiscreteDividend(0.4, 1.5), new DiscreteDividend(0.9, 1.5)};
        assertTrue(pde(OptionType.CALL, 100, 100, 1.0, 0.04, 0.0, 0.2, dividends, 200, 200, false)
                < pde(OptionType.CALL, 100, 100, 1.0, 0.04, 0.0, 0.2, null, 200, 200, false));
        assertTrue(pde(OptionType.PUT, 100, 100, 1.0, 0.04, 0.0, 0.2, dividends, 200, 200, false)
                > pde(OptionType.PUT, 100, 100, 1.0, 0.04, 0.0, 0.2, null, 200, 200, false));
    }

    @Test
    void theExDividendDateIsNotSnappedToTheTimeGrid() {
        // With 100 steps over a year a step is 0.01: dates 0.5010 and 0.5090 fall in the same step. A solver that
        // snaps them to the grid prices both identically; the true value changes slightly with the date.
        double early = pde(OptionType.CALL, 100, 100, 1.0, 0.05, 0.0, 0.2, new DiscreteDividend[]{new DiscreteDividend(0.5010, 3.0)}, 200, 100, false);
        double late = pde(OptionType.CALL, 100, 100, 1.0, 0.05, 0.0, 0.2, new DiscreteDividend[]{new DiscreteDividend(0.5090, 3.0)}, 200, 100, false);

        assertNotEquals(early, late, 1e-9);
        assertEquals(early, late, 5e-3, "but only slightly");
    }

    @Test
    void americanPutWithADividendIsAtLeastTheEuropeanOne() {
        DiscreteDividend[] dividends = {new DiscreteDividend(0.5, 4.0)};
        double european = pde(OptionType.PUT, 100, 100, 1.0, 0.05, 0.0, 0.25, dividends, 300, 300, false);
        double american = pde(OptionType.PUT, 100, 100, 1.0, 0.05, 0.0, 0.25, dividends, 300, 300, true);
        assertTrue(american >= european - 1e-9);
        assertTrue(american > european + 1e-4, "early exercise has value for a dividend-paying put");
    }

    // ---------------- validation ----------------

    @Test
    void degenerateGridsAndNonFiniteInputsAreRejectedNotDividedByZero() {
        OptionParameters p = new OptionParameters(100, 100, 1.0, 0.05, 0.2, 0.0);
        assertThrows(IllegalArgumentException.class, () -> DiscreteDividendPricer.price(OptionType.CALL, p, null, 1, 100, false));
        assertThrows(IllegalArgumentException.class, () -> DiscreteDividendPricer.price(OptionType.CALL, p, null, 100, 0, false));
        assertThrows(IllegalArgumentException.class, () -> DiscreteDividendPricer.price(OptionType.CALL, p, null, 100, -5, false));
        assertThrows(IllegalArgumentException.class, () -> pde(OptionType.CALL, Double.NaN, 100, 1, 0.05, 0, 0.2, null, 100, 100, false));
        assertThrows(IllegalArgumentException.class, () -> pde(OptionType.CALL, 100, 100, 1, Double.NaN, 0, 0.2, null, 100, 100, false));
    }

    @Test
    void expiredOrZeroVolatilityOptionsReturnIntrinsicValue() {
        assertEquals(5.0, pde(OptionType.CALL, 105, 100, 0.0, 0.05, 0, 0.2, null, 100, 100, false), 0.0);
        assertEquals(0.0, pde(OptionType.PUT, 105, 100, 0.0, 0.05, 0, 0.2, null, 100, 100, true), 0.0);
    }
}
