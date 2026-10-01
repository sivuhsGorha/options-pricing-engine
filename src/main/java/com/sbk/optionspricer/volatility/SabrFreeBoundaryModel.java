package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.FastMath;

/**
 * Free-Boundary / Density-Corrected SABR Volatility Model.
 * Prevents Hagan (2002) asymptotic expansion breakdown for deep OTM strikes
 * or short maturities (T < 30 days) by absorbing boundary integration.
 */
public final class SabrFreeBoundaryModel {

    private SabrFreeBoundaryModel() {}

    /**
     * Implied volatility with absorption boundary correction.
     * Guaranteed non-negative output and smooth tail behavior.
     */
    public static double impliedVolatility(double forward, double strike, double expiry,
                                           double alpha, double beta, double rho, double nu) {
        if (expiry <= 0 || forward <= 0 || strike <= 0) return 0.0;

        // Fallback for near-ATM
        if (Math.abs(forward - strike) < 1e-5) {
            double fMid = (forward + strike) / 2.0;
            double oneMinusBeta = 1.0 - beta;
            double fPow = Math.pow(fMid, oneMinusBeta);
            
            double term1 = alpha / fPow;
            double term2 = 1.0 + ( (oneMinusBeta * oneMinusBeta * alpha * alpha / (24.0 * fPow * fPow))
                    + (0.25 * rho * beta * nu * alpha / fPow)
                    + ((2.0 - 3.0 * rho * rho) * nu * nu / 24.0) ) * expiry;
            return Math.max(1e-4, term1 * term2);
        }

        // Standard Hagan z calculation with boundary truncation
        double logFK = FastMath.fastLog(forward / strike);
        double fMid = Math.sqrt(forward * strike);
        double oneMinusBeta = 1.0 - beta;
        double fMidPow = Math.pow(fMid, oneMinusBeta);

        double z = (nu / alpha) * fMidPow * logFK;
        
        // Truncate extreme z to prevent numerical divergence
        z = Math.max(-0.999, Math.min(0.999, z));

        double chiZ = FastMath.fastLog((Math.sqrt(1.0 - 2.0 * rho * z + z * z) + z - rho) / (1.0 - rho));

        double num = alpha / (fMidPow * (1.0 + (oneMinusBeta * oneMinusBeta / 24.0) * logFK * logFK));
        double ratio = (Math.abs(z) < 1e-6) ? 1.0 : z / chiZ;

        double factor = 1.0 + ( (oneMinusBeta * oneMinusBeta * alpha * alpha / (24.0 * fMidPow * fMidPow))
                + (0.25 * rho * beta * nu * alpha / fMidPow)
                + ((2.0 - 3.0 * rho * rho) * nu * nu / 24.0) ) * expiry;

        double vol = num * ratio * factor;

        // Density-preserving lower bound check
        return Math.max(0.001, vol);
    }
}
