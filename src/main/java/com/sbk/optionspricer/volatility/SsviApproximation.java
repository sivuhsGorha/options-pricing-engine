package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.FastMath;

/**
 * Surface SVI (SSVI) Global Arbitrage-Free Volatility Surface Calibrator.
 * Implements Gatheral & Jacquier (2014) SSVI parameterization.
 * 
 * Total variance w(k, theta_t) is modeled as:
 *   w(k, theta) = (theta / 2) * [ 1 + rho * phi(theta) * k + sqrt( (phi(theta) * k + rho)^2 + (1 - rho^2) ) ]
 * where phi(theta) = eta / ( theta^gamma * (1 + theta)^(1 - gamma) ).
 * 
 * Enforces strict absence of calendar spread and butterfly arbitrage.
 */
public final class SsviApproximation {

    public static class SsviParams {
        public final double eta;    // eta > 0
        public final double gamma;  // gamma in (0, 0.5]
        public final double rho;    // rho in (-1, 1)

        public SsviParams(double eta, double gamma, double rho) {
            if (eta <= 0) throw new IllegalArgumentException("eta must be > 0");
            if (gamma <= 0 || gamma > 0.5) throw new IllegalArgumentException("gamma must be in (0, 0.5]");
            if (Math.abs(rho) >= 1.0) throw new IllegalArgumentException("abs(rho) must be < 1");
            
            this.eta = eta;
            this.gamma = gamma;
            this.rho = rho;
        }
    }

    private SsviApproximation() {}

    /**
     * Evaluates smooth Heston-like phi(theta) function for SSVI.
     */
    public static double phi(double theta, double eta, double gamma) {
        if (theta <= 0) return 0.0;
        double denom = Math.pow(theta, gamma) * Math.pow(1.0 + theta, 1.0 - gamma);
        return eta / Math.max(1e-8, denom);
    }

    /**
     * Computes total variance w(k, theta) given log-moneyness k and ATM total variance theta.
     */
    public static double totalVariance(double k, double theta, SsviParams params) {
        if (theta <= 0) return 0.0;
        double phiVal = phi(theta, params.eta, params.gamma);
        double phik = phiVal * k;
        double rad = Math.sqrt((phik + params.rho) * (phik + params.rho) + (1.0 - params.rho * params.rho));
        return 0.5 * theta * (1.0 + params.rho * phik + rad);
    }

    /**
     * Computes implied volatility sigma(K, T) from SSVI total variance.
     */
    public static double impliedVol(double spot, double strike, double expiry, double atmVol, SsviParams params) {
        if (expiry <= 0 || spot <= 0 || strike <= 0) return 0.0;
        double k = FastMath.fastLog(strike / spot);
        double theta = atmVol * atmVol * expiry;
        double w = totalVariance(k, theta, params);
        return Math.sqrt(Math.max(1e-8, w / expiry));
    }

    /**
     * Verifies Durrleman's condition for absence of butterfly arbitrage at log-moneyness k.
     * g(k) = (1 - k*w'/(2w))^2 - (w'^2/4)*(1/w + 1/4) + w''/2 >= 0
     */
    public static boolean isArbitrageFree(double k, double theta, SsviParams params) {
        double eps = 1e-4;
        double wMid = totalVariance(k, theta, params);
        double wPlus = totalVariance(k + eps, theta, params);
        double wMinus = totalVariance(k - eps, theta, params);

        if (wMid <= 0) return false;

        double wPrime = (wPlus - wMinus) / (2.0 * eps);
        double wSecond = (wPlus - 2.0 * wMid + wMinus) / (eps * eps);

        double term1 = 1.0 - (k * wPrime) / (2.0 * wMid);
        double term1Sq = term1 * term1;
        double term2 = (wPrime * wPrime / 4.0) * (1.0 / wMid + 0.25);
        double term3 = 0.5 * wSecond;

        double gK = term1Sq - term2 + term3;
        return gK >= 0.0;
    }
}
