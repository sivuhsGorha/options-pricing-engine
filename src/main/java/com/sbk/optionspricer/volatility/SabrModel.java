package com.sbk.optionspricer.volatility;

/**
 * Stochastic Alpha Beta Rho (SABR) Volatility Model.
 * Implements the Hagan et al. (2002) asymptotic expansion for the Black implied volatility.
 *
 * <p>Known limits of the expansion (they are properties of the formula, not of this implementation): it is
 * an asymptotic approximation in the maturity, becomes inaccurate for long expiries and very low strikes, and
 * can imply negative density (butterfly arbitrage) in the wings. It is not an arbitrage-free model.
 */
public class SabrModel {

    /**
     * Calculates the SABR implied volatility for a given strike.
     *
     * @param f     Forward price (S * e^{(r-q)t})
     * @param k     Strike price
     * @param t     Time to expiry
     * @param alpha Initial volatility (scale parameter), positive
     * @param beta  CEV parameter in [0, 1] (0 = normal, 1 = lognormal)
     * @param rho   Correlation between asset price and volatility, in (-1, 1)
     * @param nu    Volatility of volatility, non-negative
     * @return Log-normal (Black) implied volatility
     * @throws IllegalArgumentException for non-finite or out-of-range parameters (a zero volatility is not returned as a sentinel)
     */
    public static double impliedVolatility(double f, double k, double t, double alpha, double beta, double rho, double nu) {
        if (!Double.isFinite(f) || f <= 0) throw new IllegalArgumentException("forward must be finite and positive");
        if (!Double.isFinite(k) || k <= 0) throw new IllegalArgumentException("strike must be finite and positive");
        if (!Double.isFinite(t) || t <= 0) throw new IllegalArgumentException("expiry must be finite and positive");
        if (!Double.isFinite(alpha) || alpha <= 0) throw new IllegalArgumentException("alpha must be finite and positive");
        if (!Double.isFinite(beta) || beta < 0 || beta > 1) throw new IllegalArgumentException("beta must be in [0, 1]");
        if (!Double.isFinite(rho) || Math.abs(rho) >= 1) throw new IllegalArgumentException("rho must be in (-1, 1)");
        if (!Double.isFinite(nu) || nu < 0) throw new IllegalArgumentException("nu must be finite and non-negative");

        double oneMinusBeta = 1.0 - beta;
        double logFk = Math.log(f / k);
        double fkPow = Math.pow(f * k, oneMinusBeta / 2.0);

        double correction = ((oneMinusBeta * oneMinusBeta) / 24.0) * (alpha * alpha / (fkPow * fkPow))
                + (rho * beta * nu * alpha) / (4.0 * fkPow)
                + ((2.0 - 3.0 * rho * rho) / 24.0) * nu * nu;
        double timeFactor = 1.0 + correction * t;

        // At the money (relative test, so the scale of the forward does not matter) the expansion reduces to
        // alpha / f^(1-beta) times the time factor.
        if (Math.abs(logFk) < 1e-12) {
            return (alpha / fkPow) * timeFactor;
        }

        double denominator2 = 1.0 + ((oneMinusBeta * oneMinusBeta) / 24.0) * logFk * logFk
                + (Math.pow(oneMinusBeta, 4) / 1920.0) * Math.pow(logFk, 4);
        double z = (nu / alpha) * fkPow * logFk;
        return (alpha / (fkPow * denominator2)) * zOverChi(z, rho) * timeFactor;
    }

    /**
     * z / x(z) with x(z) = ln[(sqrt(1 - 2 rho z + z^2) + z - rho) / (1 - rho)], evaluated without cancellation.
     * The textbook form takes the log of a number that is 1 + z for small z, losing about -log10(z) digits;
     * writing the argument as 1 + u with u computed from the difference of square roots, and using log1p, keeps
     * full precision for every z (and the limit is 1 at z = 0).
     */
    static double zOverChi(double z, double rho) {
        if (z == 0.0) {
            return 1.0;
        }
        double root = Math.sqrt(1.0 - 2.0 * rho * z + z * z);
        // (root + z - rho)/(1 - rho) - 1 = (root - 1 + z) / (1 - rho), and root - 1 = (z^2 - 2 rho z) / (root + 1)
        double u = (z + (z * z - 2.0 * rho * z) / (root + 1.0)) / (1.0 - rho);
        return z / Math.log1p(u);
    }
}
