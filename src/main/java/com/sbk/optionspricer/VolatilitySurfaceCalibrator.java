package com.sbk.optionspricer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Calibrates a smooth implied volatility surface from discretized option quotes.
 * The implementation uses the existing Black-Scholes solver to invert mid-prices,
 * while retaining a practical fallback for missing or degenerate input data.
 */
public final class VolatilitySurfaceCalibrator {
    private VolatilitySurfaceCalibrator() {
    }

    public static double calibrateImpliedVolatility(
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
        if (!Double.isFinite(timeToExpiry) || timeToExpiry <= 0.0) {
            throw new IllegalArgumentException("timeToExpiry must be finite and positive");
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
        if (!Double.isFinite(marketPrice) || marketPrice <= 0.0) {
            return 0.20;
        }

        OptionalDouble impliedVol = ImpliedVolatilitySolver.solve(
                type,
                spot,
                strike,
                timeToExpiry,
                riskFreeRate,
                dividendYield,
                marketPrice,
                new double[5]
        );
        if (impliedVol.isPresent()) {
            return Math.max(1e-6, Math.min(5.0, impliedVol.getAsDouble()));
        }

        return fallbackSmileEstimate(spot, strike, timeToExpiry, riskFreeRate, dividendYield, type, marketPrice);
    }

    public static double calibrateImpliedVolatility(OptionQuote quote, double spot, double riskFreeRate, double dividendYield) {
        if (quote == null) {
            throw new IllegalArgumentException("quote must not be null");
        }
        double timeToExpiry = Math.max(1e-6, (quote.expiry().toEpochDay() - java.time.LocalDate.now().toEpochDay()) / 365.0);
        return calibrateImpliedVolatility(
                spot,
                quote.strike(),
                timeToExpiry,
                riskFreeRate,
                dividendYield,
                quote.type(),
                quote.midPrice()
        );
    }

    public static List<OptionQuote> calibrateChain(
            String symbol,
            java.time.LocalDate expiry,
            double spot,
            List<OptionQuote> quotes,
            double riskFreeRate,
            double dividendYield
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (!Double.isFinite(spot) || spot <= 0.0) {
            throw new IllegalArgumentException("spot must be finite and positive");
        }
        if (quotes == null || quotes.isEmpty()) {
            return List.of();
        }

        List<OptionQuote> calibrated = new ArrayList<>();
        for (OptionQuote quote : quotes) {
            double timeToExpiry = Math.max(1e-6, (quote.expiry().toEpochDay() - java.time.LocalDate.now().toEpochDay()) / 365.0);
            double iv = calibrateImpliedVolatility(spot, quote.strike(), timeToExpiry, riskFreeRate, dividendYield, quote.type(), quote.midPrice());
            calibrated.add(new OptionQuote(
                    symbol,
                    expiry,
                    quote.strike(),
                    quote.type(),
                    quote.bid(),
                    quote.ask(),
                    iv,
                    quote.volume(),
                    quote.openInterest()
            ));
        }
        calibrated.sort(Comparator.comparingDouble(OptionQuote::strike));
        return calibrated;
    }

    private static double fallbackSmileEstimate(
            double spot,
            double strike,
            double timeToExpiry,
            double riskFreeRate,
            double dividendYield,
            OptionType type,
            double marketPrice
    ) {
        double moneyness = strike / spot;
        double skew = Math.log(moneyness) * (0.35 + (riskFreeRate - dividendYield) * timeToExpiry);
        double baseVol = 0.20 + Math.abs(skew) * 0.35;
        double freshness = Math.max(1e-6, Math.min(1.0, 1.0 / Math.sqrt(Math.max(timeToExpiry, 1e-6))));
        double pricePenalty = Math.max(0.0, (marketPrice / Math.max(spot, 1e-6)) - 0.5);
        return Math.max(1e-6, Math.min(5.0, baseVol + 0.12 * pricePenalty * freshness + (type == OptionType.PUT ? 0.02 : 0.0)));
    }
}
