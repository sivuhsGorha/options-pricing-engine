package com.sbk.optionspricer;

/**
 * Low-precision approximations of exp, log, sqrt and the normal CDF/PDF.
 *
 * <p><strong>These trade accuracy for nothing you should rely on.</strong> Measured over the ranges tested
 * (see {@code MathAndConventionsTest}): {@code fastExp} relative error up to 1.6e-7, {@code fastLog} absolute
 * error up to 1.1e-6, {@code fastSqrt} relative error up to 1.5e-6, {@code fastCdf} absolute error up to 7.4e-8
 * (it is the Abramowitz &amp; Stegun 26.2.17 rational approximation), {@code fastPdf} up to 4.5e-8.
 * {@link Math#sqrt} is a hardware instruction on current JVMs and {@link NormalDistribution#cdf} is exact to
 * double precision, so prefer them wherever the value feeds a price. Earlier documentation described these as
 * Chebyshev/Remez minimax polynomials; they are a degree-6 Taylor polynomial (exp), a short odd series in
 * (m-1)/(m+1) (log) and two Newton steps from a bit-pattern guess (sqrt).
 *
 * <p>Edge cases follow IEEE conventions: log of a negative or NaN is NaN, log(0) is -infinity, log(+infinity) is
 * +infinity, sqrt of a negative is NaN.
 */
public final class FastMath {

    private FastMath() {}

    private static final double LN2 = 0.6931471805599453;
    private static final double INV_LN2 = 1.4426950408889634; // 1 / ln(2)
    private static final double ONE_OVER_SQRT_2PI = 0.3989422804014327; // 1 / sqrt(2*pi)

    /**
     * exp(x) by argument reduction x = k*ln(2) + r, a degree-6 Taylor polynomial for exp(r) on
     * [-ln2/2, ln2/2], and exact scaling by 2^k through the exponent bits. Relative error up to ~1.6e-7.
     */
    public static double fastExp(double x) {
        if (x < -700.0) return 0.0;
        if (x > 700.0) return Double.POSITIVE_INFINITY;

        // k = round(x / ln2)
        int k = (int) Math.round(x * INV_LN2);
        double r = x - k * LN2;

        double p = 1.0 + r * (1.0 + r * (0.5 + r * (0.16666666666666666 + r * (0.041666666666666664 + r * (0.008333333333333333 + r * 0.001388888888888889)))));

        // Scale by 2^k via bit manipulation
        long bits = ((long) (k + 1023)) << 52;
        return p * Double.longBitsToDouble(bits);
    }

    /**
     * ln(x) from the exponent/mantissa decomposition and a short series for ln((1+u)/(1-u)), u = (m-1)/(m+1).
     * Absolute error up to ~1.1e-6.
     */
    public static double fastLog(double x) {
        if (Double.isNaN(x) || x < 0.0) return Double.NaN;
        if (x == 0.0) return Double.NEGATIVE_INFINITY;
        if (x == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
        if (x < Double.MIN_NORMAL) {
            return fastLog(x * 0x1p54) - 54.0 * LN2; // subnormals have no implicit leading 1 in the mantissa bits
        }

        long bits = Double.doubleToLongBits(x);
        int exp = (int) ((bits >> 52) & 0x7FF) - 1023;
        long mantissaBits = (bits & 0x000FFFFFFFFFFFFFL) | 0x3FF0000000000000L;
        double m = Double.longBitsToDouble(mantissaBits); // m in [1.0, 2.0)

        double u = (m - 1.0) / (m + 1.0);
        double u2 = u * u;
        double p = u * (2.0 + u2 * (0.6666666666666666 + u2 * (0.4 + u2 * (0.2857142857142857 + u2 * 0.2222222222222222))));

        return exp * LN2 + p;
    }

    /**
     * sqrt(x) from a bit-pattern initial guess and two Newton-Raphson steps. Relative error up to ~1.5e-6;
     * {@link Math#sqrt} is exact and at least as fast, so this exists only for completeness.
     */
    public static double fastSqrt(double x) {
        if (Double.isNaN(x) || x < 0.0) return Double.NaN;
        if (x == 0.0 || x == Double.POSITIVE_INFINITY || x < Double.MIN_NORMAL) return Math.sqrt(x);
        long bits = Double.doubleToLongBits(x);
        bits = (bits >> 1) + 0x1FF8000000000000L;
        double y = Double.longBitsToDouble(bits);
        y = 0.5 * (y + x / y);
        y = 0.5 * (y + x / y);
        return y;
    }

    /**
     * Phi(x) by the Abramowitz &amp; Stegun 26.2.17 rational approximation (absolute error up to ~7.4e-8) with
     * {@link #fastExp} inside. Use {@link NormalDistribution#cdf} when the value feeds a price.
     */
    public static double fastCdf(double x) {
        if (x < -7.0) return 0.0;
        if (x > 7.0) return 1.0;

        double absX = x < 0 ? -x : x;
        double k = 1.0 / (1.0 + 0.2316419 * absX);
        double poly = k * (0.319381530 + k * (-0.356563782 + k * (1.781477937 + k * (-1.821255978 + k * 1.330274429))));
        double pdf = ONE_OVER_SQRT_2PI * fastExp(-0.5 * absX * absX);
        double cdf = 1.0 - pdf * poly;

        return x < 0 ? 1.0 - cdf : cdf;
    }

    /** Standard normal density using {@link #fastExp} (absolute error up to ~4.5e-8). */
    public static double fastPdf(double x) {
        return ONE_OVER_SQRT_2PI * fastExp(-0.5 * x * x);
    }
}
