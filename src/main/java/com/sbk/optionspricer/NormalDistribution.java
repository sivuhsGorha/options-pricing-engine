package com.sbk.optionspricer;

/**
 * Standard normal distribution functions.
 *
 * Java's standard library has no built-in error function (erf), which the
 * normal CDF depends on, so it's implemented here directly using the
 * Abramowitz & Stegun 7.1.26 rational approximation (max absolute error
 * ~1.5e-7) — accurate enough for option pricing, and avoids pulling in a
 * numerical library dependency for one function.
 */
public class NormalDistribution {

    private static final double A1 = 0.254829592;
    private static final double A2 = -0.284496736;
    private static final double A3 = 1.421413741;
    private static final double A4 = -1.453152027;
    private static final double A5 = 1.061405429;
    private static final double P = 0.3275911;

    /** Standard normal probability density function, phi(x). */
    public static double pdf(double x) {
        return Math.exp(-x * x / 2.0) / Math.sqrt(2.0 * Math.PI);
    }

    /** Standard normal cumulative distribution function, Phi(x). */
    public static double cdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private static double erf(double x) {
        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x);

        double t = 1.0 / (1.0 + P * x);
        double y = 1.0 - (((((A5 * t + A4) * t) + A3) * t + A2) * t + A1) * t * Math.exp(-x * x);

        return sign * y;
    }
}
