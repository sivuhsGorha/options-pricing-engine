package com.sbk.optionspricer;

import java.util.OptionalDouble;

/**
 * Solves for the implied volatility that makes the Black-Scholes price
 * match a given observed market price — the reverse of normal pricing.
 *
 * Enforces strict input validation and no-arbitrage lower/upper price bounds.
 * Returns an OptionalDouble.empty() typed failure if no valid root exists.
 */
public class ImpliedVolatilitySolver {

    private static final int MAX_NEWTON_ITERATIONS = 50;
    private static final double PRICE_TOLERANCE = 1e-6;
    private static final double MIN_VEGA = 1e-8;
    private static final int MAX_BISECTION_ITERATIONS = 100;
    private static final double VOL_LOWER_BOUND = 1e-6;
    private static final double VOL_UPPER_BOUND = 5.0; // 500% annualized vol ceiling

    public static OptionalDouble solve(OptionType type, OptionParameters knownParams, double marketPrice) {
        double[] scratch = new double[5];
        return solve(type, knownParams.spot(), knownParams.strike(), knownParams.timeToExpiry(), knownParams.riskFreeRate(), knownParams.dividendYield(), marketPrice, scratch);
    }

    public static OptionalDouble solve(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double dividendYield, double marketPrice, double[] scratchGreeks) {
        // 1. Input Validation
        if (Double.isNaN(spot) || Double.isNaN(strike) || Double.isNaN(timeToExpiry) ||
            Double.isNaN(riskFreeRate) || Double.isNaN(dividendYield) || Double.isNaN(marketPrice) ||
            Double.isInfinite(spot) || Double.isInfinite(strike) || Double.isInfinite(timeToExpiry) ||
            Double.isInfinite(riskFreeRate) || Double.isInfinite(dividendYield) || Double.isInfinite(marketPrice)) {
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

        // 3. Newton-Raphson Solver
        OptionalDouble newtonResult = newtonRaphson(type, spot, strike, timeToExpiry, riskFreeRate, dividendYield, marketPrice, scratchGreeks);
        if (newtonResult.isPresent()) {
            return newtonResult;
        }

        // 4. Bisection Fallback
        return bisection(type, spot, strike, timeToExpiry, riskFreeRate, dividendYield, marketPrice);
    }

    private static OptionalDouble newtonRaphson(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double dividendYield, double marketPrice, double[] scratchGreeks) {
        double vol = 0.3; // 30% initial guess

        for (int i = 0; i < MAX_NEWTON_ITERATIONS; i++) {
            double price = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, vol, dividendYield);
            double diff = price - marketPrice;

            if (Math.abs(diff) < PRICE_TOLERANCE) {
                return OptionalDouble.of(vol);
            }

            BlackScholesPricer.greeks(type, spot, strike, timeToExpiry, riskFreeRate, vol, dividendYield, scratchGreeks);
            double vega = scratchGreeks[2];
            if (Math.abs(vega) < MIN_VEGA) {
                return OptionalDouble.empty();
            }

            vol -= diff / vega;
            if (vol <= 0 || Double.isNaN(vol) || vol > VOL_UPPER_BOUND) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    private static OptionalDouble bisection(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double dividendYield, double marketPrice) {
        double lo = VOL_LOWER_BOUND;
        double hi = VOL_UPPER_BOUND;

        double priceLo = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, lo, dividendYield) - marketPrice;
        double priceHi = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, hi, dividendYield) - marketPrice;

        // Verify endpoint bracket signs
        if (priceLo > 0.0 || priceHi < 0.0) {
            return OptionalDouble.empty(); // Market price is outside [price(VOL_LOWER), price(VOL_UPPER)]
        }

        for (int i = 0; i < MAX_BISECTION_ITERATIONS; i++) {
            double mid = (lo + hi) / 2.0;
            double priceMid = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, mid, dividendYield) - marketPrice;

            if (Math.abs(priceMid) < PRICE_TOLERANCE) {
                return OptionalDouble.of(mid);
            }
            if (Math.signum(priceMid) == Math.signum(priceLo)) {
                lo = mid;
                priceLo = priceMid;
            } else {
                hi = mid;
            }
        }

        double finalPrice = BlackScholesPricer.price(type, spot, strike, timeToExpiry, riskFreeRate, (lo + hi) / 2.0, dividendYield);
        if (Math.abs(finalPrice - marketPrice) < 1e-4) {
            return OptionalDouble.of((lo + hi) / 2.0);
        }
        return OptionalDouble.empty();
    }
}
