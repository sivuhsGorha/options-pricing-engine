package com.sbk.optionspricer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Inverts option mid-prices into Black-Scholes implied volatilities.
 *
 * <p>A quote that cannot be inverted (no usable price, a price outside the no-arbitrage bounds, or no
 * time left) yields no volatility. Earlier versions returned a made-up 20% or a heuristic "smile" in
 * those cases, which callers could not tell apart from a real implied volatility.
 *
 * <p>The valuation date is explicit so results are reproducible; time to expiry is ACT/365 calendar days.
 */
public final class VolatilitySurfaceCalibrator {
    private VolatilitySurfaceCalibrator() {
    }

    /** Implied volatility of one quote, or empty if it cannot be inverted. */
    public static OptionalDouble impliedVolatility(OptionQuote quote, double spot, double riskFreeRate, double dividendYield, LocalDate asOf) {
        if (quote == null) {
            throw new IllegalArgumentException("quote must not be null");
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf must not be null");
        }
        return impliedVolatility(spot, quote.strike(), yearsBetween(asOf, quote.expiry()), riskFreeRate, dividendYield, quote.type(), quote.midPrice());
    }

    public static OptionalDouble impliedVolatility(
            double spot,
            double strike,
            double timeToExpiry,
            double riskFreeRate,
            double dividendYield,
            OptionType type,
            double marketPrice
    ) {
        if (!Double.isFinite(spot) || spot <= 0.0) {
            throw new IllegalArgumentException("spot must be finite and positive");
        }
        if (!Double.isFinite(strike) || strike <= 0.0) {
            throw new IllegalArgumentException("strike must be finite and positive");
        }
        if (!Double.isFinite(riskFreeRate)) {
            throw new IllegalArgumentException("riskFreeRate must be finite");
        }
        if (!Double.isFinite(dividendYield)) {
            throw new IllegalArgumentException("dividendYield must be finite");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (!Double.isFinite(timeToExpiry) || timeToExpiry <= 0.0) {
            return OptionalDouble.empty(); // expired or expiring now: volatility is not identifiable
        }
        if (!Double.isFinite(marketPrice) || marketPrice <= 0.0) {
            return OptionalDouble.empty();
        }
        return ImpliedVolatilitySolver.solve(type, spot, strike, timeToExpiry, riskFreeRate, dividendYield, marketPrice, new double[5]);
    }

    /**
     * Implied volatilities for the quotes of one expiry, sorted by strike. Quotes for other expiries are
     * ignored, and quotes that cannot be inverted are omitted (an {@link OptionQuote} cannot carry a missing vol).
     */
    public static List<OptionQuote> calibrateChain(
            String symbol,
            LocalDate expiry,
            double spot,
            List<OptionQuote> quotes,
            double riskFreeRate,
            double dividendYield,
            LocalDate asOf
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf must not be null");
        }
        if (!Double.isFinite(spot) || spot <= 0.0) {
            throw new IllegalArgumentException("spot must be finite and positive");
        }
        if (quotes == null || quotes.isEmpty()) {
            return List.of();
        }

        double timeToExpiry = yearsBetween(asOf, expiry);
        List<OptionQuote> calibrated = new ArrayList<>();
        for (OptionQuote quote : quotes) {
            if (!expiry.equals(quote.expiry())) {
                continue;
            }
            OptionalDouble iv = impliedVolatility(spot, quote.strike(), timeToExpiry, riskFreeRate, dividendYield, quote.type(), quote.midPrice());
            if (iv.isEmpty()) {
                continue;
            }
            calibrated.add(new OptionQuote(symbol, expiry, quote.strike(), quote.type(), quote.bid(), quote.ask(),
                    iv.getAsDouble(), quote.volume(), quote.openInterest()));
        }
        calibrated.sort(Comparator.comparingDouble(OptionQuote::strike));
        return calibrated;
    }

    private static double yearsBetween(LocalDate from, LocalDate to) {
        return TimeConventions.yearFraction(from, to);
    }
}
