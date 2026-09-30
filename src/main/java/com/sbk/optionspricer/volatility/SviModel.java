package com.sbk.optionspricer.volatility;

/**
 * Stochastic Volatility Inspired (SVI) Model.
 * Specifically implements the Raw SVI parametrization introduced by Gatheral (2004).
 * 
 * Guarantees absence of static arbitrage if parameters are constrained correctly,
 * making it the industry standard for equity options pricing surfaces.
 */
public class SviModel {

    /**
     * Calculates the SVI implied variance for a given log-moneyness.
     * To get implied volatility, simply return Math.sqrt(variance / t).
     *
     * @param k Log-moneyness: ln(K / F)
     * @param a Base level of variance (a >= 0)
     * @param b Slope of the variance wings (b >= 0)
     * @param rho Correlation / symmetry of the smile (-1 <= rho <= 1)
     * @param m Shift of the smile minimum from ATM
     * @param sigma Smoothness of the vertex (sigma > 0)
     * @return Total implied variance (w = vol^2 * t)
     */
    public static double impliedVariance(double k, double a, double b, double rho, double m, double sigma) {
        // Raw SVI Formula: w(k) = a + b * (rho * (k - m) + sqrt((k - m)^2 + sigma^2))
        double diff = k - m;
        return a + b * (rho * diff + Math.sqrt(diff * diff + sigma * sigma));
    }
    
    /**
     * Converts Total Implied Variance back to Annualized Implied Volatility.
     */
    public static double impliedVolatility(double variance, double timeToExpiry) {
        if (variance <= 0 || timeToExpiry <= 0) return 0.0;
        return Math.sqrt(variance / timeToExpiry);
    }
}
