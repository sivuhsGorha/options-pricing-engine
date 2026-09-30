package com.sbk.optionspricer.volatility;

/**
 * Stochastic Alpha Beta Rho (SABR) Volatility Model.
 * Implements the Hagan (2002) asymptotic expansion formula for implied volatility.
 * 
 * Commonly used for interest rate and FX derivatives, but adaptable for equity indices
 * due to its excellent modeling of the volatility smile and forward dynamics.
 */
public class SabrModel {

    /**
     * Calculates the SABR implied volatility for a given strike.
     *
     * @param f     Forward price (S * e^{(r-q)t})
     * @param k     Strike price
     * @param t     Time to expiry
     * @param alpha Initial volatility (scale parameter)
     * @param beta  CEV parameter (0 = normal, 1 = lognormal, typically 0.5-1.0 for equities)
     * @param rho   Correlation between asset price and volatility brownian motions
     * @param nu    Volatility of volatility (vol-of-vol)
     * @return Log-normal (Black) implied volatility
     */
    public static double impliedVolatility(double f, double k, double t, double alpha, double beta, double rho, double nu) {
        if (f <= 0 || k <= 0 || t <= 0) return 0.0;
        
        double oneMinusBeta = 1.0 - beta;
        double fK = f * k;
        double logFk = Math.log(f / k);
        
        // ATM Expansion
        if (Math.abs(f - k) < 1e-7) {
            double term1 = alpha / Math.pow(f, oneMinusBeta);
            double term2 = ((oneMinusBeta * oneMinusBeta) / 24.0) * (alpha * alpha / Math.pow(f, 2.0 * oneMinusBeta));
            double term3 = (rho * beta * nu * alpha) / (4.0 * Math.pow(f, oneMinusBeta));
            double term4 = ((2.0 - 3.0 * rho * rho) / 24.0) * nu * nu;
            return term1 * (1.0 + (term2 + term3 + term4) * t);
        }
        
        // Non-ATM Expansion
        double denominator1 = Math.pow(fK, oneMinusBeta / 2.0);
        double denominator2 = 1.0 + ((oneMinusBeta * oneMinusBeta) / 24.0) * logFk * logFk 
                            + ((Math.pow(oneMinusBeta, 4)) / 1920.0) * Math.pow(logFk, 4);
        
        double z = (nu / alpha) * denominator1 * logFk;
        double x = Math.log((Math.sqrt(1.0 - 2.0 * rho * z + z * z) + z - rho) / (1.0 - rho));
        
        double multiplier = 1.0 + (
                ((oneMinusBeta * oneMinusBeta) / 24.0) * (alpha * alpha / Math.pow(fK, oneMinusBeta))
                + (rho * beta * nu * alpha) / (4.0 * denominator1)
                + ((2.0 - 3.0 * rho * rho) / 24.0) * nu * nu
        ) * t;
        
        return (alpha / (denominator1 * denominator2)) * (z / x) * multiplier;
    }
}
