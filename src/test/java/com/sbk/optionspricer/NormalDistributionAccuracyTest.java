package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks Phi(x) against an independent high-precision reference (the Maclaurin series of erf evaluated
 * in BigDecimal), not against another floating-point approximation.
 */
class NormalDistributionAccuracyTest {

    private static final String PI_DIGITS =
            "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211706798214808651";

    /** Phi(x) = (1 + erf(x / sqrt 2)) / 2, computed with {@code digits} significant digits. */
    private static BigDecimal referencePhi(double x, int digits) {
        MathContext mc = new MathContext(digits);
        BigDecimal pi = new BigDecimal(PI_DIGITS);
        BigDecimal z = new BigDecimal(x).divide(new BigDecimal(2).sqrt(mc), mc);
        BigDecimal z2 = z.multiply(z, mc);
        BigDecimal term = z;          // z^(2n+1) / n!, with the sign and 1/(2n+1) applied when summing
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal epsilon = BigDecimal.ONE.movePointLeft(digits + 5);
        for (int n = 0; n < 6000; n++) {
            BigDecimal contribution = term.divide(new BigDecimal(2 * n + 1), mc);
            sum = (n % 2 == 0) ? sum.add(contribution, mc) : sum.subtract(contribution, mc);
            term = term.multiply(z2, mc).divide(new BigDecimal(n + 1), mc);
            if (term.abs().compareTo(epsilon) < 0 && n > 5) break;
        }
        BigDecimal erf = sum.multiply(new BigDecimal(2), mc).divide(pi.sqrt(mc), mc);
        return BigDecimal.ONE.add(erf, mc).divide(new BigDecimal(2), mc);
    }

    private static double absError(double x, BigDecimal reference) {
        return new BigDecimal(NormalDistribution.cdf(x)).subtract(reference).abs().doubleValue();
    }

    private static double relError(double x, BigDecimal reference) {
        return new BigDecimal(NormalDistribution.cdf(x)).subtract(reference).abs()
                .divide(reference.abs(), new MathContext(30, RoundingMode.HALF_EVEN)).doubleValue();
    }

    @Test
    void absoluteErrorIsAtDoublePrecisionAcrossTheWholeRange() {
        double worst = 0.0;
        double worstX = 0.0;
        for (double x = -8.0; x <= 8.0; x += 0.0125) {
            double error = absError(x, referencePhi(x, 60));
            if (error > worst) {
                worst = error;
                worstX = x;
            }
        }
        // One ulp of a value near 0.5 is 1.1e-16, so a few ulps is the floor for double precision.
        assertTrue(worst <= 5e-16, "max absolute error " + worst + " at x=" + worstX + " (the A&S 7.1.26 formula was ~7e-8)");
    }

    @Test
    void relativeErrorStaysSmallDeepInTheLowerTailWherePricesAreTiny() {
        // A 1e-16-sized absolute error is a 100% error on Phi(-8) = 6e-16; the tail must be computed directly.
        for (double x : new double[]{-1.0, -2.0, -3.5, -5.0, -6.0, -7.0, -8.0, -10.0, -15.0, -20.0}) {
            BigDecimal reference = referencePhi(x, 320);
            assertTrue(relError(x, reference) <= 1e-12, "x=" + x + " relative error " + relError(x, reference));
        }
    }

    @Test
    void knownValues() {
        assertEquals(0.5, NormalDistribution.cdf(0.0), 0.0);
        assertEquals(0.9750021048517795, NormalDistribution.cdf(1.96), 1e-15);
        assertEquals(0.8413447460685429, NormalDistribution.cdf(1.0), 1e-15);
        assertEquals(6.220960574271786e-16, NormalDistribution.cdf(-8.0), 6.2e-16 * 1e-11);
    }

    @Test
    void symmetryBoundsAndMonotonicity() {
        double previous = -1.0;
        for (double x = -9.0; x <= 9.0; x += 0.001) {
            double p = NormalDistribution.cdf(x);
            assertTrue(p >= 0.0 && p <= 1.0, "outside [0,1] at " + x);
            assertTrue(p >= previous, "not monotonic at " + x);
            assertEquals(1.0, p + NormalDistribution.cdf(-x), 2.3e-16, "Phi(x) + Phi(-x) at " + x);
            previous = p;
        }
    }

    @Test
    void nonFiniteAndExtremeInputs() {
        assertTrue(Double.isNaN(NormalDistribution.cdf(Double.NaN)));
        assertEquals(1.0, NormalDistribution.cdf(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(0.0, NormalDistribution.cdf(Double.NEGATIVE_INFINITY), 0.0);
        assertEquals(1.0, NormalDistribution.cdf(40.0), 0.0);
        assertEquals(0.0, NormalDistribution.cdf(-40.0), 0.0);
        assertTrue(NormalDistribution.cdf(-30.0) > 0.0, "still representable at -30");
    }

    @Test
    void putCallParityHoldsToRoundingAcrossRandomParameters() {
        Random random = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            double s = 20 + random.nextDouble() * 480;
            double k = 20 + random.nextDouble() * 480;
            double t = 0.01 + random.nextDouble() * 3;
            double r = random.nextDouble() * 0.08;
            double q = random.nextDouble() * 0.04;
            double vol = 0.05 + random.nextDouble() * 0.9;
            double call = BlackScholesPricer.price(OptionType.CALL, s, k, t, r, vol, q);
            double put = BlackScholesPricer.price(OptionType.PUT, s, k, t, r, vol, q);
            double parity = s * Math.exp(-q * t) - k * Math.exp(-r * t);
            assertEquals(parity, call - put, 1e-11 * Math.max(s, k), "parity at s=" + s + " k=" + k + " t=" + t + " vol=" + vol);
        }
    }

    @Test
    void pricesAreNeverNegativeEvenWhenFarOutOfTheMoney() {
        for (double k : new double[]{1.0, 10.0, 50.0, 100.0, 500.0, 5_000.0, 50_000.0}) {
            for (double vol : new double[]{0.01, 0.05, 0.2, 1.0}) {
                for (OptionType type : OptionType.values()) {
                    double price = BlackScholesPricer.price(type, 100.0, k, 0.25, 0.03, vol, 0.0);
                    assertTrue(price >= 0.0 && Double.isFinite(price), type + " k=" + k + " vol=" + vol + " price=" + price);
                }
            }
        }
    }

    @Test
    void theSimdPathAgreesWithTheScalarPathToRounding() {
        double[] strikes = new double[1024];
        for (int i = 0; i < strikes.length; i++) strikes[i] = 40.0 + i * 0.2;
        for (boolean call : new boolean[]{true, false}) {
            double[] simd = VectorBlackScholesPricer.priceBatchParallelSingleThread(100.0, strikes, 0.5, 0.04, 0.3, call);
            for (int i = 0; i < strikes.length; i++) {
                double scalar = BlackScholesPricer.price(call ? OptionType.CALL : OptionType.PUT, 100.0, strikes[i], 0.5, 0.04, 0.3, 0.0);
                assertEquals(scalar, simd[i], 1e-12, "strike " + strikes[i] + (call ? " call" : " put"));
            }
        }
    }
}
