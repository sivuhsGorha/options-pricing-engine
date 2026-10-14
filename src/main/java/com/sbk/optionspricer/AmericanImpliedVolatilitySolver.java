package com.sbk.optionspricer;

import com.sbk.optionspricer.models.tree.AmericanTreePricer;

import java.util.OptionalDouble;

/**
 * Solves for implied volatility honoring American early-exercise rights.
 *
 * <p>For out-of-the-money (OTM) options (calls with S <= K, puts with S >= K), the early exercise
 * premium is zero, so the European Black-Scholes inversion is exact and fast.
 *
 * <p>For in-the-money (ITM) options (calls with S > K, puts with S < K), American early exercise
 * has positive economic value. Inverting with European formulas attributes this early-exercise
 * cash premium to volatility, distorting the implied volatility smile. This solver inverts ITM options
 * using {@link AmericanTreePricer} with a safeguarded bracketed Brent/Bisection root finder.
 */
public final class AmericanImpliedVolatilitySolver {

    private static final int TREE_STEPS = 80;
    private static final int MAX_ITERATIONS = 40;
    private static final double VOL_TOLERANCE = 1e-5;

    private AmericanImpliedVolatilitySolver() {}

    public static OptionalDouble solve(
            OptionType type,
            double spot,
            double strike,
            double timeToExpiry,
            double riskFreeRate,
            double dividendYield,
            double marketPrice
    ) {
        if (!Double.isFinite(spot) || spot <= 0.0 || !Double.isFinite(strike) || strike <= 0.0
                || !Double.isFinite(timeToExpiry) || timeToExpiry <= 0.0
                || !Double.isFinite(marketPrice) || marketPrice <= 0.0) {
            return OptionalDouble.empty();
        }

        // Check if option is OTM/ATM:
        boolean isItm = (type == OptionType.CALL) ? (spot > strike) : (spot < strike);
        if (!isItm) {
            // Early exercise premium is zero; European inversion is exact
            return ImpliedVolatilitySolver.solve(type, spot, strike, timeToExpiry, riskFreeRate, dividendYield, marketPrice, new double[5]);
        }

        // For ITM options, check American intrinsic lower bound: price must exceed intrinsic
        double intrinsic = (type == OptionType.CALL) ? (spot - strike) : (strike - spot);
        if (marketPrice < intrinsic - 1e-6) {
            return OptionalDouble.empty(); // Violates American no-arbitrage lower bound
        }

        // Calculate minimum stable volatility for trinomial tree: sigma^2 >= 0.5 * (r-q)^2 * dt
        double minStableVol = Math.max(0.015, Math.abs(riskFreeRate - dividendYield) * Math.sqrt(timeToExpiry / (2.0 * TREE_STEPS)) * 1.10);
        double lo = minStableVol;
        double hi = 3.0; // 300% vol upper bound
        OptionParameters pLo = new OptionParameters(spot, strike, timeToExpiry, riskFreeRate, lo, dividendYield);
        OptionParameters pHi = new OptionParameters(spot, strike, timeToExpiry, riskFreeRate, hi, dividendYield);

        double fLo = americanPrice(type, pLo, TREE_STEPS) - marketPrice;
        double fHi = americanPrice(type, pHi, TREE_STEPS) - marketPrice;

        if (fLo > 0.0) {
            if (Math.abs(marketPrice - intrinsic) < 0.05) {
                return OptionalDouble.of(lo);
            }
            return OptionalDouble.empty();
        }
        if (fHi < 0.0) {
            return OptionalDouble.empty(); // Exceeds 300% vol
        }

        // Bracketed root search (Illinois method with Bisection fallback)
        double a = lo, b = hi;
        double fa = fLo, fb = fHi;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            if (Math.abs(b - a) < VOL_TOLERANCE || Math.abs(fb) < 1e-4) {
                return OptionalDouble.of(b);
            }

            double step = fb * (b - a) / (fb - fa);
            double next = b - step;

            if (next <= a || next >= b || Double.isNaN(next)) {
                next = 0.5 * (a + b);
            }

            OptionParameters pNext = new OptionParameters(spot, strike, timeToExpiry, riskFreeRate, next, dividendYield);
            double fNext = americanPrice(type, pNext, TREE_STEPS) - marketPrice;

            if (fNext * fb < 0.0) {
                a = b;
                fa = fb;
                b = next;
                fb = fNext;
            } else {
                b = next;
                fb = fNext;
                fa *= 0.5;
            }
        }

        return OptionalDouble.of(b);
    }

    private static double americanPrice(OptionType type, OptionParameters p, int steps) {
        try {
            return AmericanTreePricer.price(type, p, steps);
        } catch (IllegalArgumentException e) {
            // Fall back to Crank-Nicolson PDE pricer if tree parameters hit probability bounds
            return com.sbk.optionspricer.models.pde.DiscreteDividendPricer.price(
                    type, p, new com.sbk.optionspricer.models.pde.DiscreteDividendPricer.DiscreteDividend[0], 100, 100, true);
        }
    }
}
