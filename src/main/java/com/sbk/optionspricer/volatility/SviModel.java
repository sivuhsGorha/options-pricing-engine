package com.sbk.optionspricer.volatility;

/**
 * Stochastic Volatility Inspired (SVI) model, Gatheral's raw parametrization (2004).
 *
 * <p>The parameter checks here guarantee non-negative total variance for every k. They do not by themselves
 * guarantee a surface free of static arbitrage: butterfly arbitrage across strikes and calendar arbitrage across
 * maturities need separate conditions (see {@link SsviApproximation} for a parametrization with closed-form ones).
 */
public class SviModel {

    /**
     * Total implied variance w(k) = a + b (rho (k - m) + sqrt((k - m)^2 + sigma^2)).
     *
     * @param k     log-moneyness ln(K / F)
     * @param a     level of variance
     * @param b     slope of the wings, b &gt;= 0
     * @param rho   tilt, -1 &lt; rho &lt; 1
     * @param m     horizontal shift of the smile
     * @param sigma smoothness of the vertex, sigma &gt; 0
     * @throws IllegalArgumentException for invalid parameters, including those with a + b sigma sqrt(1 - rho^2) &lt; 0,
     *                                  whose minimum variance is negative (this used to be silently returned)
     */
    public static double impliedVariance(double k, double a, double b, double rho, double m, double sigma) {
        if (!Double.isFinite(k) || !Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(rho)
                || !Double.isFinite(m) || !Double.isFinite(sigma)) {
            throw new IllegalArgumentException("SVI inputs must be finite");
        }
        if (b < 0.0) throw new IllegalArgumentException("b must be non-negative");
        if (!(Math.abs(rho) < 1.0)) throw new IllegalArgumentException("rho must be in (-1, 1)");
        if (!(sigma > 0.0)) throw new IllegalArgumentException("sigma must be positive");
        if (a + b * sigma * Math.sqrt(1.0 - rho * rho) < 0.0) {
            throw new IllegalArgumentException("a + b sigma sqrt(1 - rho^2) must be non-negative (minimum variance would be negative)");
        }
        double diff = k - m;
        return a + b * (rho * diff + Math.sqrt(diff * diff + sigma * sigma));
    }

    /**
     * Converts total implied variance to annualized implied volatility.
     */
    public static double impliedVolatility(double variance, double timeToExpiry) {
        if (!Double.isFinite(variance) || variance < 0.0) throw new IllegalArgumentException("variance must be finite and non-negative");
        if (!Double.isFinite(timeToExpiry) || timeToExpiry <= 0.0) throw new IllegalArgumentException("timeToExpiry must be finite and positive");
        return Math.sqrt(variance / timeToExpiry);
    }
}
