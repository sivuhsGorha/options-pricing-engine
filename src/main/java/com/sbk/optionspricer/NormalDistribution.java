package com.sbk.optionspricer;

/**
 * Standard normal distribution functions at double precision.
 *
 * <p>Phi(x) is computed from the complementary error function so the lower tail keeps its relative
 * accuracy (a price of 1e-12 must not be computed as a difference of two numbers near 1). erfc(z) uses
 * two classical expansions, each accurate to a few ulps where it is applied:
 * <ul>
 *   <li>z &lt; 2: erf(z) from the all-positive series {@code 2/sqrt(pi) * e^(-z^2) * sum 2^n z^(2n+1) / (2n+1)!!},
 *       then erfc = 1 - erf (erfc is at least 0.0047 there, so no meaningful digits are lost);</li>
 *   <li>z &ge; 2: the continued fraction {@code erfc(z) = e^(-z^2)/sqrt(pi) / (z + (1/2)/(z + 1/(z + (3/2)/(z + ...))))},
 *       evaluated bottom-up with a fixed number of terms.</li>
 * </ul>
 * This replaces the Abramowitz &amp; Stegun 7.1.26 approximation, whose absolute error (~7e-8 for Phi)
 * made Phi(0) = 0.5000000005 and moved option prices by about 1e-5.
 */
public class NormalDistribution {

    private static final double SQRT_2 = 1.4142135623730951;
    private static final double INV_SQRT_2PI = 0.3989422804014327;
    private static final double INV_SQRT_PI = 0.5641895835477563;
    private static final double TWO_OVER_SQRT_PI = 1.1283791670955126;
    /** Below this Phi is smaller than the smallest double; above ~8.3 it rounds to exactly 1. */
    private static final double LOWER_CUTOFF = -38.6;
    private static final double UPPER_CUTOFF = 8.3;

    /** Standard normal probability density function, phi(x). */
    public static double pdf(double x) {
        return INV_SQRT_2PI * Math.exp(-x * x / 2.0);
    }

    /** Standard normal cumulative distribution function, Phi(x). */
    public static double cdf(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        if (x <= LOWER_CUTOFF) {
            return 0.0;
        }
        if (x >= UPPER_CUTOFF) {
            return 1.0;
        }
        // Phi(-|x|) = erfc(|x| / sqrt 2) / 2 computed directly; the upper half follows by symmetry.
        double lowerTail = 0.5 * erfc(Math.abs(x) / SQRT_2);
        return x < 0.0 ? lowerTail : 1.0 - lowerTail;
    }

    /** Complementary error function for z &ge; 0. */
    static double erfc(double z) {
        if (z < 2.0) {
            return 1.0 - erfSeries(z);
        }
        return erfcContinuedFraction(z);
    }

    /** 1 / (2n + 3): multiplying by a table entry is several times cheaper than dividing in the series loop. */
    private static final double[] INV_ODD = new double[200];

    static {
        for (int n = 0; n < INV_ODD.length; n++) {
            INV_ODD[n] = 1.0 / (2.0 * n + 3.0);
        }
    }

    private static double erfSeries(double z) {
        double z2 = z * z;
        double twoZ2 = 2.0 * z2;
        double term = z;
        double sum = z;
        for (int n = 0; n < INV_ODD.length; n++) {
            term *= twoZ2 * INV_ODD[n];
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return TWO_OVER_SQRT_PI * Math.exp(-z2) * sum;
    }

    /**
     * K = z + a1/(z + a2/(z + ...)) with a_k = k/2, evaluated from the bottom up with a fixed number of terms:
     * the same terms as the forward (Lentz) method with one division each instead of two. Terms needed for a
     * relative error of 2e-16 were measured at 55 (z=2), 38 (z=2.5), 30 (z=3), 21 (z=4), 14 (z=6), which
     * 12 + 190/z^2 covers with margin.
     */
    private static double erfcContinuedFraction(double z) {
        int terms = 12 + (int) (190.0 / (z * z));
        double f = z;
        for (int k = terms; k >= 1; k--) {
            f = z + 0.5 * k / f;
        }
        return INV_SQRT_PI * Math.exp(-z * z) / f;
    }
}
