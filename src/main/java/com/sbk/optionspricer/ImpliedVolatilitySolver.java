package com.sbk.optionspricer;

import java.util.OptionalDouble;

/**
 * Solves for the Black-Scholes implied volatility that reproduces an observed option price.
 *
 * <p>The price is strictly increasing in volatility, so the root is bracketed and found with a
 * safeguarded Newton iteration: a Newton step is taken when it stays inside the bracket, otherwise the
 * bracket is bisected. Convergence is judged on the volatility itself (step below 1e-13), not on the
 * price, because an absolute price tolerance leaves a volatility error of tolerance / vega, which is
 * large for short-dated and far-out-of-the-money options where vega is tiny.
 *
 * <p>This is a <em>European</em> model: feeding it American option prices attributes the early-exercise
 * premium to volatility. Inputs are validated and prices outside the no-arbitrage bounds, or outside what
 * volatilities in [1e-6, 5.0] can produce, return an empty result rather than a wrong root.
 */
public class ImpliedVolatilitySolver {

    private static final int MAX_ITERATIONS = 100;
    private static final double VOL_STEP_TOLERANCE = 1e-13;
    private static final double MIN_VEGA = 1e-12;
    private static final double VOL_LOWER_BOUND = 1e-6;
    private static final double VOL_UPPER_BOUND = 5.0; // 500% annualized vol ceiling
    /** If iteration ends without meeting the step tolerance, accept a bracket narrower than this. */
    private static final double ACCEPTABLE_BRACKET = 1e-9;

    public static OptionalDouble solve(OptionType type, OptionParameters knownParams, double marketPrice) {
        double[] scratch = new double[5];
        return solve(type, knownParams.spot(), knownParams.strike(), knownParams.timeToExpiry(), knownParams.riskFreeRate(), knownParams.dividendYield(), marketPrice, scratch);
    }

    /** {@code scratchGreeks} is accepted for source compatibility and is no longer used. */
    public static OptionalDouble solve(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double dividendYield, double marketPrice, double[] scratchGreeks) {
        // 1. Input Validation
        if (!Double.isFinite(spot) || !Double.isFinite(strike) || !Double.isFinite(timeToExpiry)
                || !Double.isFinite(riskFreeRate) || !Double.isFinite(dividendYield) || !Double.isFinite(marketPrice)) {
            return OptionalDouble.empty();
        }
        if (spot <= 0 || strike <= 0 || timeToExpiry <= 0 || marketPrice <= 0) {
            return OptionalDouble.empty();
        }

        // 2. Arbitrage & Price Bound Checks
        double discountedSpot = spot * Math.exp(-dividendYield * timeToExpiry);
        double discountedStrike = strike * Math.exp(-riskFreeRate * timeToExpiry);
        double lowerBound = (type == OptionType.CALL)
                ? Math.max(discountedSpot - discountedStrike, 0.0)
                : Math.max(discountedStrike - discountedSpot, 0.0);
        double upperBound = (type == OptionType.CALL) ? discountedSpot : discountedStrike;
        if (marketPrice <= lowerBound + 1e-12 || marketPrice >= upperBound - 1e-12) {
            return OptionalDouble.empty();
        }

        // 3. Bracket the root. Price is increasing in vol, so f(lo) <= 0 <= f(hi) must hold.
        double lo = VOL_LOWER_BOUND;
        double hi = VOL_UPPER_BOUND;
        double fLo = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, lo, dividendYield) - marketPrice;
        double fHi = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, hi, dividendYield) - marketPrice;
        if (fLo > 0.0 || fHi < 0.0) {
            return OptionalDouble.empty(); // outside [price(1e-6 vol), price(500% vol)]
        }
        if (fLo == 0.0) return OptionalDouble.of(lo);
        if (fHi == 0.0) return OptionalDouble.of(hi);

        // 4. Safeguarded Newton.
        double vol = initialGuess(spot, strike, timeToExpiry, riskFreeRate, dividendYield, marketPrice, lo, hi);
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double f = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, vol, dividendYield) - marketPrice;
            if (f == 0.0) {
                return OptionalDouble.of(vol);
            }
            if (f > 0.0) hi = vol; else lo = vol;

            double next = Double.NaN;
            double vega = vega(spot, strike, timeToExpiry, riskFreeRate, dividendYield, vol);
            if (vega > MIN_VEGA) {
                next = vol - f / vega;
            }
            if (!(next > lo && next < hi)) {
                next = 0.5 * (lo + hi); // Newton left the bracket (or vega vanished): bisect
            }
            if (Math.abs(next - vol) <= VOL_STEP_TOLERANCE * Math.max(1.0, vol)) {
                return OptionalDouble.of(next);
            }
            vol = next;
        }
        return hi - lo < ACCEPTABLE_BRACKET ? OptionalDouble.of(0.5 * (lo + hi)) : OptionalDouble.empty();
    }

    /** Black-Scholes vega (per unit of volatility), computed directly rather than via all five Greeks. */
    private static double vega(double spot, double strike, double t, double r, double q, double vol) {
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(spot / strike) + (r - q + 0.5 * vol * vol) * t) / (vol * sqrtT);
        return spot * Math.exp(-q * t) * NormalDistribution.pdf(d1) * sqrtT;
    }

    /** Brenner-Subrahmanyam near-the-money estimate, clamped into the bracket; 30% if it is unusable. */
    private static double initialGuess(double spot, double strike, double t, double r, double q, double price, double lo, double hi) {
        double forward = spot * Math.exp((r - q) * t);
        double guess = Math.sqrt(2.0 * Math.PI / t) * price / (spot * Math.exp(-q * t));
        if (!Double.isFinite(guess) || guess <= lo || guess >= hi || Math.abs(Math.log(forward / strike)) > 0.5) {
            return 0.3;
        }
        return guess;
    }
}
