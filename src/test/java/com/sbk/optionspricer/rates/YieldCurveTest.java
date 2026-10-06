package com.sbk.optionspricer.rates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YieldCurveTest {

    @Test
    void aSinglePillarCurveExtrapolatesWithFlatZeroRateInsteadOfFailing() {
        YieldCurve curve = new YieldCurve(new double[]{2.0}, new double[]{Math.exp(-0.06)}); // zero rate 3%

        assertEquals(Math.exp(-0.03 * 5.0), curve.getDiscountFactor(5.0), 1e-14);
        assertEquals(Math.exp(-0.03 * 1.0), curve.getDiscountFactor(1.0), 1e-14);
        assertEquals(0.03, curve.getZeroRate(10.0), 1e-14);
    }

    @Test
    void interpolationIsLogLinearAndPillarsAreExact() {
        YieldCurve curve = new YieldCurve(new double[]{1, 3}, new double[]{0.97, 0.90});

        assertEquals(0.97, curve.getDiscountFactor(1), 0.0);
        assertEquals(0.90, curve.getDiscountFactor(3), 0.0);
        assertEquals(Math.sqrt(0.97 * 0.90), curve.getDiscountFactor(2), 1e-14, "midpoint of log-linear");
        assertEquals(1.0, curve.getDiscountFactor(0), 0.0);
        assertEquals(1.0, curve.getDiscountFactor(-1), 0.0);
    }

    @Test
    void theCurveCannotBeChangedThroughTheArraysItWasBuiltFrom() {
        double[] times = {1, 2};
        double[] dfs = {0.97, 0.94};
        YieldCurve curve = new YieldCurve(times, dfs);
        double before = curve.getDiscountFactor(1.5);

        times[0] = 5;
        dfs[1] = 0.01;

        assertEquals(before, curve.getDiscountFactor(1.5), 0.0);
    }

    @Test
    void invalidCurvesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{}, new double[]{}));
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{1, 2}, new double[]{0.9}));
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{2, 1}, new double[]{0.9, 0.95}), "unsorted");
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{1, 1}, new double[]{0.9, 0.9}), "duplicate times");
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{0, 1}, new double[]{1.0, 0.9}), "non-positive time");
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{1, 2}, new double[]{0.9, 0.0}), "zero discount factor");
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{1, 2}, new double[]{0.9, -0.1}), "negative discount factor");
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(new double[]{1, 2}, new double[]{0.9, Double.NaN}));
        assertThrows(IllegalArgumentException.class, () -> new YieldCurve(null, null));
    }

    @Test
    void nonFiniteQueriesDoNotProduceSilentNumbers() {
        YieldCurve curve = new YieldCurve(new double[]{1, 2}, new double[]{0.97, 0.94});
        assertThrows(IllegalArgumentException.class, () -> curve.getDiscountFactor(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> curve.getZeroRate(Double.NaN));
    }

    @Test
    void zeroRateAtZeroIsTheShortRateLimit() {
        YieldCurve curve = new YieldCurve(new double[]{1}, new double[]{Math.exp(-0.02)});
        assertEquals(0.02, curve.getZeroRate(0.0), 1e-4);
    }
}
