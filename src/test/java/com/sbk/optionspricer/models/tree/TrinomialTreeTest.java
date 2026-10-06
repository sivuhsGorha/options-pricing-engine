package com.sbk.optionspricer.models.tree;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.models.pde.DiscreteDividendPricer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrinomialTreeTest {

    @Test
    void probabilitiesAreValidAndMatchTheFirstTwoMoments() {
        OptionParameters p = new OptionParameters(100, 100, 1.0, 0.05, 0.25, 0.02);
        TrinomialTreeParameters t = TrinomialTreeParameters.calculate(p, 200);

        assertEquals(1.0, t.pUp() + t.pMid() + t.pDown(), 1e-14);
        assertTrue(t.pUp() > 0 && t.pMid() > 0 && t.pDown() > 0);

        double dx = Math.log(t.upFactor());
        double nu = 0.05 - 0.02 - 0.5 * 0.25 * 0.25;
        double mean = (t.pUp() - t.pDown()) * dx;
        double secondMoment = (t.pUp() + t.pDown()) * dx * dx;
        assertEquals(nu * t.dt(), mean, 1e-14, "mean of the log-return per step");
        assertEquals(0.25 * 0.25 * t.dt() + Math.pow(nu * t.dt(), 2), secondMoment, 1e-14, "second moment per step");
    }

    @Test
    void aTreeWhoseProbabilitiesWouldBeNegativeIsRejectedWithAnActionableMessage() {
        // Large drift, tiny volatility, few steps: pMid < 0, so the tree is not a valid probability model.
        OptionParameters p = new OptionParameters(100, 100, 10.0, 0.5, 0.01, 0.0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TrinomialTreeParameters.calculate(p, 5));
        assertTrue(e.getMessage().toLowerCase().contains("steps"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AmericanTreePricer.price(OptionType.CALL, p, 5));
    }

    @Test
    void invalidStepCountsAndInputsAreRejected() {
        OptionParameters p = new OptionParameters(100, 100, 1.0, 0.05, 0.2, 0.0);
        assertThrows(IllegalArgumentException.class, () -> AmericanTreePricer.price(OptionType.PUT, p, 0));
        assertThrows(IllegalArgumentException.class, () -> AmericanTreePricer.price(OptionType.PUT, p, -3));
        assertThrows(IllegalArgumentException.class, () -> TrinomialTreeParameters.calculate(p, 0));
    }

    @Test
    void americanPutMatchesTheFiniteDifferenceSolution() {
        double[][] cases = {
                // s, k, t, r, q, vol
                {100, 100, 1.0, 0.05, 0.0, 0.20},
                {100, 110, 0.5, 0.08, 0.0, 0.30},
                {100, 90, 2.0, 0.04, 0.02, 0.35},
        };
        for (double[] c : cases) {
            OptionParameters p = new OptionParameters(c[0], c[1], c[2], c[3], c[5], c[4]);
            double pde = DiscreteDividendPricer.price(OptionType.PUT, p, null, 1600, 1600, true);
            double tree = AmericanTreePricer.price(OptionType.PUT, p, 2000);
            assertEquals(pde, tree, 1.5e-3, java.util.Arrays.toString(c));
        }
    }

    @Test
    void americanCallWithoutDividendsEqualsTheEuropeanCall() {
        // Early exercise of a call on a non-dividend-paying asset is never optimal.
        OptionParameters p = new OptionParameters(100, 95, 1.0, 0.04, 0.3, 0.0);
        double european = BlackScholesPricer.price(OptionType.CALL, p);
        assertEquals(european, AmericanTreePricer.price(OptionType.CALL, p, 2000), 1.5e-3);
    }

    @Test
    void americanCallOnADividendYieldHasAnEarlyExercisePremium() {
        OptionParameters p = new OptionParameters(100, 100, 1.0, 0.03, 0.25, 0.07);
        double european = BlackScholesPricer.price(OptionType.CALL, p);
        double american = AmericanTreePricer.price(OptionType.CALL, p, 1500);
        double pde = DiscreteDividendPricer.price(OptionType.CALL, p, null, 1200, 1200, true);

        assertTrue(american > european + 0.05, "american " + american + " european " + european);
        assertEquals(pde, american, 2e-3);
    }

    @Test
    void largeTreesRunInReasonableTime() {
        // Per-node Math.pow made this quadratic in transcendental calls.
        OptionParameters p = new OptionParameters(100, 100, 1.0, 0.05, 0.2, 0.0);
        long start = System.nanoTime();
        double price = AmericanTreePricer.price(OptionType.PUT, p, 6000);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(price > 6.0 && price < 6.2);
        assertTrue(millis < 4_000, "6000-step tree took " + millis + "ms");
    }

    @Test
    void zeroTimeAndZeroVolatilityReturnIntrinsic() {
        assertEquals(7.0, AmericanTreePricer.price(OptionType.PUT, new OptionParameters(93, 100, 0.0, 0.05, 0.2, 0.0), 10), 0.0);
        assertEquals(0.0, AmericanTreePricer.price(OptionType.CALL, new OptionParameters(93, 100, 1.0, 0.05, 0.0, 0.0), 10), 0.0);
    }
}
