package com.sbk.optionspricer;

/**
 * Solves for the implied volatility that makes the Black-Scholes price
 * match a given observed market price — the reverse of normal pricing
 * (given volatility, get a price; here, given a price, get the volatility).
 *
 * Uses Newton-Raphson (fast, converges in a handful of iterations near the
 * solution) as the primary method, since vega (the derivative needed for
 * Newton-Raphson) is directly available from {@link BlackScholesPricer}.
 *
 * Numerical stability: Newton-Raphson can fail to converge, or even diverge,
 * when vega is very small (deep in/out-of-the-money options, or very short
 * time to expiry) — dividing by a near-zero derivative is unstable. This
 * implementation detects that case and falls back to bisection, which is
 * slower but guaranteed to converge as long as a valid bracket exists.
 */
public class ImpliedVolatilitySolver {

    private static final int MAX_NEWTON_ITERATIONS = 50;
    private static final double PRICE_TOLERANCE = 1e-6;
    private static final double MIN_VEGA = 1e-8;
    private static final int MAX_BISECTION_ITERATIONS = 100;
    private static final double VOL_LOWER_BOUND = 1e-6;
    private static final double VOL_UPPER_BOUND = 5.0; // 500% annualized vol as a practical ceiling

    public static double solve(OptionType type, OptionParameters knownParams, double marketPrice) {
        double vol = newtonRaphson(type, knownParams, marketPrice);
        if (!Double.isNaN(vol)) {
            return vol;
        }
        // Newton-Raphson failed to converge cleanly — fall back to bisection.
        return bisection(type, knownParams, marketPrice);
    }

    private static double newtonRaphson(OptionType type, OptionParameters knownParams, double marketPrice) {
        double vol = 0.3; // reasonable starting guess: 30% annualized vol

        for (int i = 0; i < MAX_NEWTON_ITERATIONS; i++) {
            OptionParameters trial = withVolatility(knownParams, vol);
            double price = BlackScholesPricer.price(type, trial);
            double diff = price - marketPrice;

            if (Math.abs(diff) < PRICE_TOLERANCE) {
                return vol;
            }

            double vega = BlackScholesPricer.greeks(type, trial).vega();
            if (Math.abs(vega) < MIN_VEGA) {
                return Double.NaN; // signal: too unstable, caller should fall back
            }

            vol -= diff / vega;
            if (vol <= 0 || Double.isNaN(vol)) {
                return Double.NaN; // stepped outside a sane range, bail to bisection
            }
        }
        return Double.NaN; // did not converge within the iteration budget
    }

    private static double bisection(OptionType type, OptionParameters knownParams, double marketPrice) {
        double lo = VOL_LOWER_BOUND;
        double hi = VOL_UPPER_BOUND;

        double priceLo = BlackScholesPricer.price(type, withVolatility(knownParams, lo)) - marketPrice;

        for (int i = 0; i < MAX_BISECTION_ITERATIONS; i++) {
            double mid = (lo + hi) / 2.0;
            double priceMid = BlackScholesPricer.price(type, withVolatility(knownParams, mid)) - marketPrice;

            if (Math.abs(priceMid) < PRICE_TOLERANCE) {
                return mid;
            }
            if (Math.signum(priceMid) == Math.signum(priceLo)) {
                lo = mid;
                priceLo = priceMid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0; // best estimate after exhausting iteration budget
    }

    private static OptionParameters withVolatility(OptionParameters original, double newVolatility) {
        return new OptionParameters(
                original.spot(), original.strike(), original.timeToExpiry(),
                original.riskFreeRate(), newVolatility, original.dividendYield()
        );
    }
}
