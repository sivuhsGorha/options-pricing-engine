package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Documented error bounds, edge cases, one day-count convention, and results that do not depend on array length. */
class MathAndConventionsTest {

    // ---------------- FastMath: the bounds its Javadoc states ----------------

    @Test
    void fastApproximationsStayWithinTheirDocumentedErrorBounds() {
        double exp = 0, log = 0, sqrt = 0, cdf = 0, pdf = 0;
        for (double x = -30; x <= 30; x += 0.0137) exp = Math.max(exp, Math.abs(FastMath.fastExp(x) / Math.exp(x) - 1));
        for (double x = 1e-6; x <= 1e6; x *= 1.013) log = Math.max(log, Math.abs(FastMath.fastLog(x) - Math.log(x)));
        for (double x = 1e-6; x <= 1e8; x *= 1.013) sqrt = Math.max(sqrt, Math.abs(FastMath.fastSqrt(x) / Math.sqrt(x) - 1));
        for (double x = -7; x <= 7; x += 0.001) {
            cdf = Math.max(cdf, Math.abs(FastMath.fastCdf(x) - NormalDistribution.cdf(x)));
            pdf = Math.max(pdf, Math.abs(FastMath.fastPdf(x) - NormalDistribution.pdf(x)));
        }
        assertTrue(exp <= 2e-7, "fastExp relative error " + exp);
        assertTrue(log <= 1.5e-6, "fastLog absolute error " + log);
        assertTrue(sqrt <= 2e-6, "fastSqrt relative error " + sqrt);
        assertTrue(cdf <= 1e-7, "fastCdf absolute error " + cdf);
        assertTrue(pdf <= 6e-8, "fastPdf absolute error " + pdf);
    }

    @Test
    void fastMathEdgeCasesFollowIeeeConventionsInsteadOfInventingNumbers() {
        assertEquals(Double.POSITIVE_INFINITY, FastMath.fastLog(Double.POSITIVE_INFINITY), "log(+inf)");
        assertTrue(Double.isNaN(FastMath.fastLog(Double.NaN)));
        assertTrue(Double.isNaN(FastMath.fastLog(-1.0)));
        assertEquals(Double.NEGATIVE_INFINITY, FastMath.fastLog(0.0), "log(0)");

        assertTrue(Double.isNaN(FastMath.fastSqrt(-4.0)), "sqrt of a negative is NaN, not 0");
        assertEquals(0.0, FastMath.fastSqrt(0.0), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, FastMath.fastSqrt(Double.POSITIVE_INFINITY));
        assertTrue(Double.isNaN(FastMath.fastSqrt(Double.NaN)));

        assertTrue(Double.isNaN(FastMath.fastExp(Double.NaN)));
        assertEquals(0.0, FastMath.fastExp(Double.NEGATIVE_INFINITY));
        assertEquals(Double.POSITIVE_INFINITY, FastMath.fastExp(Double.POSITIVE_INFINITY));
    }

    // ---------------- SimdMath ----------------

    @Test
    void simdSqrtGivesTheSameAnswerForAnElementWhetherItSitsInTheVectorBodyOrTheTail() {
        // The vector body uses an exact IEEE sqrt; the scalar tail used a ~1.5e-6 approximation, so the value of
        // an element depended on the length of the array it was in.
        for (int length = 1; length <= 37; length++) {
            double[] src = new double[length];
            double[] dst = new double[length];
            for (int i = 0; i < length; i++) src[i] = 1.0 + i * 3.7;
            SimdMath.sqrt(src, dst, length);
            for (int i = 0; i < length; i++) {
                assertEquals(Math.sqrt(src[i]), dst[i], 0.0, "length " + length + " index " + i);
            }
        }
    }

    // ---------------- one day-count convention ----------------

    @Test
    void yearFractionIsActual365Fixed() {
        assertEquals(1.0, TimeConventions.yearFraction(LocalDate.of(2025, 3, 3), LocalDate.of(2026, 3, 3)), 1e-15);
        assertEquals(30.0 / 365.0, TimeConventions.yearFraction(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31)), 1e-15);
        assertEquals(366.0 / 365.0, TimeConventions.yearFraction(LocalDate.of(2023, 12, 31), LocalDate.of(2024, 12, 31)), 1e-15, "a leap year is 366/365, not 1");
        assertEquals(0.0, TimeConventions.yearFraction(LocalDate.of(2025, 5, 5), LocalDate.of(2025, 5, 5)), 0.0);
        assertTrue(TimeConventions.yearFraction(LocalDate.of(2025, 5, 6), LocalDate.of(2025, 5, 5)) < 0, "negative once past");
        assertThrows(IllegalArgumentException.class, () -> TimeConventions.yearFraction(null, LocalDate.now()));
    }

    // ---------------- Main's pricing line ----------------

    @Test
    void theParityLineShowsBothSidesOfThePutCallRelationAndTheirDifference() {
        String line = Main.pricingSummary(100.0, 105.0, 0.5, 0.05, 0.25, 0.0);

        // C - P is -2.4075 here, which is correct (it equals S - K e^{-rT}), so it must not be labelled as a parity figure.
        assertTrue(line.contains("C - P = -2.4075"), line);
        assertTrue(line.contains("S*exp(-qT) - K*exp(-rT) = -2.4075"), line);
        assertTrue(line.matches("(?s).*parity error = [-]?[0-9.]+E-1[0-9].*") || line.contains("parity error = 0.0"), line);
        assertFalse(line.contains("Parity: -2.4075"), "the old label implied a violation");
    }

    @Test
    void theParityErrorIsRoundingNoiseAcrossInputs() {
        for (double strike : new double[]{60, 100, 140}) {
            for (double expiry : new double[]{0.05, 1.0, 3.0}) {
                String line = Main.pricingSummary(100.0, strike, expiry, 0.03, 0.4, 0.02);
                String tail = line.substring(line.indexOf("parity error = ") + "parity error = ".length()).trim();
                double error = Double.parseDouble(tail.split("[\\s|]")[0]);
                assertTrue(Math.abs(error) < 1e-10, "strike " + strike + " expiry " + expiry + ": " + line);
            }
        }
    }
}
