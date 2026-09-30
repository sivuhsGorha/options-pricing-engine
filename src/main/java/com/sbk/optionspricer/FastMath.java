package com.sbk.optionspricer;

/**
 * Ultra-fast mathematical approximations for high-frequency quantitative options pricing.
 * Bypasses native C libm overhead with argument-reduced Chebyshev/Remez minimax polynomials,
 * IEEE 754 bit-manipulation for exponentiation and logarithms, and fast cdf evaluation.
 */
public final class FastMath {

    private FastMath() {}

    private static final double LN2 = 0.6931471805599453;
    private static final double INV_LN2 = 1.4426950408889634; // 1 / ln(2)
    private static final double SQRT_2_PI = 0.7978845608028654; // sqrt(2/pi)
    private static final double ONE_OVER_SQRT_2PI = 0.3989422804014327; // 1 / sqrt(2*pi)

    /**
     * Fast exp(x) approximation using argument reduction x = k*ln(2) + r
     * and bitwise IEEE 754 double scaling for 2^k.
     */
    public static double fastExp(double x) {
        if (x < -700.0) return 0.0;
        if (x > 700.0) return Double.POSITIVE_INFINITY;

        // k = round(x / ln2)
        int k = (int) Math.round(x * INV_LN2);
        double r = x - k * LN2;

        // 6th degree Horner scheme polynomial for exp(r), r in [-ln2/2, ln2/2]
        double p = 1.0 + r * (1.0 + r * (0.5 + r * (0.16666666666666666 + r * (0.041666666666666664 + r * (0.008333333333333333 + r * 0.001388888888888889)))));

        // Scale by 2^k via bit manipulation
        long bits = ((long) (k + 1023)) << 52;
        return p * Double.longBitsToDouble(bits);
    }

    /**
     * Fast log(x) approximation using mantissa/exponent bit decomposition
     * and Padé / series representation of ln((1+u)/(1-u)).
     */
    public static double fastLog(double x) {
        if (x <= 0.0) return Double.NaN;

        long bits = Double.doubleToLongBits(x);
        int exp = (int) ((bits >> 52) & 0x7FF) - 1023;
        long mantissaBits = (bits & 0x000FFFFFFFFFFFFFL) | 0x3FF0000000000000L;
        double m = Double.longBitsToDouble(mantissaBits); // m in [1.0, 2.0)

        double u = (m - 1.0) / (m + 1.0);
        double u2 = u * u;

        // 9th degree polynomial for ln((1+u)/(1-u))
        double p = u * (2.0 + u2 * (0.6666666666666666 + u2 * (0.4 + u2 * (0.2857142857142857 + u2 * 0.2222222222222222))));

        return exp * LN2 + p;
    }

    /**
     * Fast sqrt(x) using doubleToLongBits initial bit shift guess (Quake III style for double)
     * followed by 2 Newton-Raphson iterations.
     */
    public static double fastSqrt(double x) {
        if (x <= 0.0) return 0.0;
        long bits = Double.doubleToLongBits(x);
        // Bit magic initial estimate for double precision sqrt
        bits = (bits >> 1) + 0x1FF8000000000000L;
        double y = Double.longBitsToDouble(bits);
        // 2 Newton-Raphson steps
        y = 0.5 * (y + x / y);
        y = 0.5 * (y + x / y);
        return y;
    }

    /**
     * Fast Cumulative Normal Distribution Function (CDF) using Hart's rational approximation.
     */
    public static double fastCdf(double x) {
        if (x < -7.0) return 0.0;
        if (x > 7.0) return 1.0;

        double absX = x < 0 ? -x : x;
        // Rational approximation: CDF(x) = 1 - pdf(x) * rational(absX)
        double k = 1.0 / (1.0 + 0.2316419 * absX);
        double poly = k * (0.319381530 + k * (-0.356563782 + k * (1.781477937 + k * (-1.821255978 + k * 1.330274429))));
        double pdf = ONE_OVER_SQRT_2PI * fastExp(-0.5 * absX * absX);
        double cdf = 1.0 - pdf * poly;

        return x < 0 ? 1.0 - cdf : cdf;
    }

    /**
     * Fast Standard Normal Probability Density Function (PDF).
     */
    public static double fastPdf(double x) {
        return ONE_OVER_SQRT_2PI * fastExp(-0.5 * x * x);
    }
}
