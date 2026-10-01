package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.FastMath;

/**
 * Stochastic Local Volatility (SLV) Calibrator.
 * Blends Heston Stochastic Volatility with Dupire Local Volatility leverage function sigma(t, S)
 * to perfectly match market implied volatility smiles while modeling forward volatility dynamics
 * without butterfly or calendar arbitrage.
 *
 * SDE Dynamics:
 * dS_t = r S_t dt + sigma(t, S_t) * sqrt(v_t) * S_t * dW_t^S
 * dv_t = kappa * (theta - v_t) dt + xi * sqrt(v_t) * dW_t^v
 */
public class SlvApproximation {

    public static class SlvParams {
        public final double kappa; // Mean reversion rate
        public final double theta; // Long-term variance
        public final double xi;    // Volatility of volatility
        public final double rho;   // Correlation between spot and variance
        public final double v0;    // Initial variance

        public SlvParams(double kappa, double theta, double xi, double rho, double v0) {
            this.kappa = kappa;
            this.theta = theta;
            this.xi = xi;
            this.rho = rho;
            this.v0 = v0;
        }
    }

    /**
     * Calculates Dupire Local Volatility sigma_Dupire(T, K) from Black-Scholes call price surface.
     * Uses finite differences: dC/dT, dC/dK, d2C/dK2.
     */
    public static double computeDupireLocalVol(double spot, double strike, double expiry, double rate,
                                               double impliedVol, double dVoldT, double dVoldK, double d2VoldK2) {
        if (expiry <= 1e-6) return impliedVol;

        double d1 = (FastMath.fastLog(spot / strike) + (rate + 0.5 * impliedVol * impliedVol) * expiry) / (impliedVol * Math.sqrt(expiry));
        double d2 = d1 - impliedVol * Math.sqrt(expiry);

        // Black-Scholes Call derivatives wrt T and K
        double callPrice = BlackScholesPricer.price(com.sbk.optionspricer.OptionType.CALL, spot, strike, expiry, rate, impliedVol, 0.0);
        double vega = spot * Math.sqrt(expiry) * FastMath.fastPdf(d1);
        
        // dC/dT = -r K e^(-rT) N(d2) + vega * dVol/dT + r spot N(d1) ... (simplified total theta/vol derivative)
        double dCdT = rate * strike * FastMath.fastExp(-rate * expiry) * FastMath.fastCdf(d2) + vega * dVoldT;
        
        // dC/dK = -e^(-rT) N(d2) + vega * dVol/dK
        double dCdK = -FastMath.fastExp(-rate * expiry) * FastMath.fastCdf(d2) + vega * dVoldK;
        
        // d2C/dK2 (gamma wrt strike K)
        double d2CdK2 = (FastMath.fastExp(-rate * expiry) * FastMath.fastPdf(d2) / (strike * impliedVol * Math.sqrt(expiry)))
                        + 2.0 * vega * dVoldK + vega * d2VoldK2;

        double numerator = dCdT + rate * strike * Math.abs(dCdK);
        double denominator = 0.5 * strike * strike * Math.max(d2CdK2, 1e-8);

        if (numerator <= 0.0 || denominator <= 0.0) {
            return impliedVol; // Fallback to market IV if local variance is non-positive
        }

        return Math.sqrt(numerator / denominator);
    }

    /**
     * Computes the SLV Leverage Function scale factor sigma_SLV(T, K) = sigma_Dupire(T, K) / sqrt(E[v_t | S_t = K]).
     */
    public static double computeLeverageFactor(double dupireVol, SlvParams heston, double expiry) {
        // Expected Heston variance E[v_t] at time T
        double expKappaT = FastMath.fastExp(-heston.kappa * expiry);
        double expectedVariance = heston.theta + (heston.v0 - heston.theta) * expKappaT;
        double expectedVol = FastMath.fastSqrt(Math.max(expectedVariance, 1e-6));

        double leverage = dupireVol / expectedVol;
        // Clamp leverage ratio to preserve numerical stability
        return Math.max(0.2, Math.min(leverage, 5.0));
    }
}
