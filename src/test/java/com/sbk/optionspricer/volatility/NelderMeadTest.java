package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NelderMeadTest {

    private static double rosenbrock(double[] p) {
        double x = p[0], y = p[1];
        return (1 - x) * (1 - x) + 100 * (y - x * x) * (y - x * x);
    }

    @Test
    void findsTheRosenbrockMinimum() {
        NelderMead.Result r = NelderMead.minimize(NelderMeadTest::rosenbrock, new double[]{-1.2, 1.0}, new double[]{0.5, 0.5}, 20_000, 1e-16);

        assertTrue(r.converged(), "iterations " + r.iterations());
        assertEquals(1.0, r.point()[0], 1e-4);
        assertEquals(1.0, r.point()[1], 1e-4);
        assertTrue(r.value() < 1e-8);
    }

    @Test
    void recoversAThreeParameterQuadratic() {
        NelderMead.Result r = NelderMead.minimize(p -> {
            double s = 0;
            for (int i = 0; i < p.length; i++) s += (p[i] - (i + 1)) * (p[i] - (i + 1));
            return s;
        }, new double[]{0, 0, 0}, new double[]{1, 1, 1}, 5_000, 1e-16);

        for (int i = 0; i < 3; i++) assertEquals(i + 1, r.point()[i], 1e-6, "parameter " + i);
    }

    @Test
    void nanRegionsAreAvoidedNotCompared() {
        NelderMead.Result r = NelderMead.minimize(p -> p[0] < 0 ? Double.NaN : (p[0] - 2) * (p[0] - 2),
                new double[]{0.5}, new double[]{1.0}, 2_000, 1e-16);

        assertEquals(2.0, r.point()[0], 1e-5);
        assertTrue(Double.isFinite(r.value()));
    }

    @Test
    void theIterationCapReturnsTheBestPointFoundAndSaysItDidNotConverge() {
        NelderMead.Result r = NelderMead.minimize(NelderMeadTest::rosenbrock, new double[]{-1.2, 1.0}, new double[]{0.5, 0.5}, 3, 1e-16);

        assertEquals(3, r.iterations());
        assertFalse(r.converged());
        assertTrue(Double.isFinite(r.value()));
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> NelderMead.minimize(NelderMeadTest::rosenbrock, new double[]{0, 0}, new double[]{1}, 10, 1e-8));
        assertThrows(IllegalArgumentException.class, () -> NelderMead.minimize(NelderMeadTest::rosenbrock, new double[]{0, 0}, new double[]{1, 1}, 0, 1e-8));
        assertThrows(IllegalArgumentException.class, () -> NelderMead.minimize(NelderMeadTest::rosenbrock, new double[]{0, 0}, new double[]{1, 1}, 10, 0.0));
    }
}
