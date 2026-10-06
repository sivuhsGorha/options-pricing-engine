package com.sbk.optionspricer.rates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OisCurveBootstrapperTest {

    /** Par residual of a swap: R * sum(accrual_j * Z(t_j)) - (1 - Z(T)); payments annually, plus a final stub. */
    private static double parResidual(YieldCurve curve, double maturity, double rate) {
        if (maturity <= 1.0) {
            return rate * maturity * curve.getDiscountFactor(maturity) - (1.0 - curve.getDiscountFactor(maturity));
        }
        double annuity = 0.0;
        int years = (int) Math.floor(maturity + 1e-12);
        for (int k = 1; k <= years; k++) {
            annuity += curve.getDiscountFactor(k);
        }
        double stub = maturity - years;
        if (stub > 1e-9) {
            annuity += stub * curve.getDiscountFactor(maturity);
        }
        return rate * annuity - (1.0 - curve.getDiscountFactor(maturity));
    }

    @Test
    void aFlatParCurveGivesAnnualCompoundingDiscountFactorsAtEveryYearEvenBetweenPillars() {
        double rate = 0.03;
        // Pillars skip years 4, 6, 8, 9: those discount factors come from interpolation and must still be exact.
        YieldCurve curve = OisCurveBootstrapper.bootstrap(
                new double[]{1, 2, 3, 5, 7, 10}, new double[]{rate, rate, rate, rate, rate, rate});

        for (int year = 1; year <= 10; year++) {
            assertEquals(Math.pow(1.0 + rate, -year), curve.getDiscountFactor(year), 1e-12, "year " + year);
        }
    }

    @Test
    void shortSwapsPayOnceAtMaturity() {
        YieldCurve curve = OisCurveBootstrapper.bootstrap(new double[]{1.0 / 12, 0.25, 0.5}, new double[]{0.04, 0.041, 0.042});

        assertEquals(1.0 / (1.0 + 0.04 / 12), curve.getDiscountFactor(1.0 / 12), 1e-14);
        assertEquals(1.0 / (1.0 + 0.041 * 0.25), curve.getDiscountFactor(0.25), 1e-14);
        assertEquals(1.0 / (1.0 + 0.042 * 0.5), curve.getDiscountFactor(0.5), 1e-14);
    }

    @Test
    void everyInputSwapReprices_atPar() {
        double[] maturities = {1.0 / 12, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 20, 30};
        double[] rates = {0.0310, 0.0318, 0.0325, 0.0331, 0.0338, 0.0342, 0.0350, 0.0356, 0.0361, 0.0366, 0.0360};
        YieldCurve curve = OisCurveBootstrapper.bootstrap(maturities, rates);

        for (int i = 0; i < maturities.length; i++) {
            assertEquals(0.0, parResidual(curve, maturities[i], rates[i]), 1e-12, "maturity " + maturities[i]);
        }
    }

    @Test
    void fractionalMaturitiesAboveOneYearIncludeTheFinalStubPeriod() {
        double[] maturities = {1, 1.5, 2.75, 4};
        double[] rates = {0.02, 0.0215, 0.0235, 0.026};
        YieldCurve curve = OisCurveBootstrapper.bootstrap(maturities, rates);

        for (int i = 0; i < maturities.length; i++) {
            assertEquals(0.0, parResidual(curve, maturities[i], rates[i]), 1e-12, "maturity " + maturities[i]);
        }
    }

    @Test
    void negativeRatesProduceDiscountFactorsAboveOne() {
        double rate = -0.005;
        YieldCurve curve = OisCurveBootstrapper.bootstrap(new double[]{1, 2, 5}, new double[]{rate, rate, rate});

        for (int year : new int[]{1, 2, 3, 4, 5}) {
            assertEquals(Math.pow(1.0 + rate, -year), curve.getDiscountFactor(year), 1e-12);
            assertTrue(curve.getDiscountFactor(year) > 1.0);
        }
    }

    @Test
    void discountFactorsAreStrictlyPositiveForAnUpwardSlopingCurve() {
        YieldCurve curve = OisCurveBootstrapper.bootstrap(new double[]{1, 2, 5, 10, 30}, new double[]{0.01, 0.02, 0.03, 0.04, 0.05});
        double previous = 1.0;
        for (double t = 0.25; t <= 30; t += 0.25) {
            double df = curve.getDiscountFactor(t);
            assertTrue(df > 0.0 && df < previous + 1e-12, "t=" + t);
            previous = df;
        }
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OisCurveBootstrapper.bootstrap(new double[]{1, 2}, new double[]{0.03}));
        assertThrows(IllegalArgumentException.class, () -> OisCurveBootstrapper.bootstrap(new double[]{}, new double[]{}));
        assertThrows(IllegalArgumentException.class, () -> OisCurveBootstrapper.bootstrap(new double[]{2, 1}, new double[]{0.03, 0.03}), "unsorted");
        assertThrows(IllegalArgumentException.class, () -> OisCurveBootstrapper.bootstrap(new double[]{1, 1}, new double[]{0.03, 0.03}), "duplicate");
        assertThrows(IllegalArgumentException.class, () -> OisCurveBootstrapper.bootstrap(new double[]{0, 1}, new double[]{0.03, 0.03}), "zero maturity");
        assertThrows(IllegalArgumentException.class, () -> OisCurveBootstrapper.bootstrap(new double[]{1, 2}, new double[]{0.03, Double.NaN}));
        assertThrows(IllegalArgumentException.class, () -> OisCurveBootstrapper.bootstrap(null, null));
    }
}
