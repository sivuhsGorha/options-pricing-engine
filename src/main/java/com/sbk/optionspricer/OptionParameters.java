package com.sbk.optionspricer;

/**
 * The inputs the Black-Scholes model needs to price a European option.
 *
 * @param spot            current price of the underlying asset (S)
 * @param strike          strike price (K)
 * @param timeToExpiry    time to expiry, in years (T) — e.g. 0.5 for 6 months
 * @param riskFreeRate    annualized risk-free interest rate, as a decimal (r) — e.g. 0.05 for 5%
 * @param volatility      annualized volatility of the underlying, as a decimal (sigma) — e.g. 0.20 for 20%
 * @param dividendYield   continuous annualized dividend yield, as a decimal (q) — 0.0 if none
 */
public record OptionParameters(
        double spot,
        double strike,
        double timeToExpiry,
        double riskFreeRate,
        double volatility,
        double dividendYield
) {
    public OptionParameters {
        if (spot <= 0) throw new IllegalArgumentException("spot must be positive");
        if (strike <= 0) throw new IllegalArgumentException("strike must be positive");
        if (timeToExpiry < 0) throw new IllegalArgumentException("timeToExpiry cannot be negative");
        if (volatility < 0) throw new IllegalArgumentException("volatility cannot be negative");
    }

    /** Convenience constructor assuming no dividend yield. */
    public static OptionParameters noDividend(double spot, double strike, double timeToExpiry,
                                               double riskFreeRate, double volatility) {
        return new OptionParameters(spot, strike, timeToExpiry, riskFreeRate, volatility, 0.0);
    }
}
