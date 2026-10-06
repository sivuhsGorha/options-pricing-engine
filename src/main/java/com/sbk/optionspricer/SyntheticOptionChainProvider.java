package com.sbk.optionspricer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple in-memory provider used for tests and fallback scenarios. It generates a
 * synthetic chain around the current spot for the requested expiry.
 */
public final class SyntheticOptionChainProvider implements OptionChainProvider {
    private final double spot;
    private final double volatility;
    private final double riskFreeRate;
    private final double dividendYield;

    public SyntheticOptionChainProvider() {
        this(100.0, 0.2, 0.05, 0.0);
    }

    public SyntheticOptionChainProvider(double spot, double volatility, double riskFreeRate, double dividendYield) {
        if (!Double.isFinite(spot) || spot <= 0.0) {
            throw new IllegalArgumentException("spot must be finite and positive");
        }
        if (!Double.isFinite(volatility) || volatility < 0.0) {
            throw new IllegalArgumentException("volatility must be finite and non-negative");
        }
        this.spot = spot;
        this.volatility = volatility;
        this.riskFreeRate = riskFreeRate;
        this.dividendYield = dividendYield;
    }

    @Override
    public OptionChain getOptionChain(String symbol, LocalDate expiry) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }

        List<OptionQuote> quotes = new ArrayList<>();
        double[] strikes = {80.0, 90.0, 95.0, 100.0, 105.0, 110.0, 120.0};
        for (double strike : strikes) {
            double timeToExpiry = Math.max(0.1, 30.0 / 365.0);
            double callPrice = BlackScholesPricer.price(OptionType.CALL, spot, strike, timeToExpiry, riskFreeRate, volatility, dividendYield);
            double putPrice = BlackScholesPricer.price(OptionType.PUT, spot, strike, timeToExpiry, riskFreeRate, volatility, dividendYield);

            double callIv = ImpliedVolatilitySolver.solve(OptionType.CALL, spot, strike, timeToExpiry, riskFreeRate, dividendYield, callPrice, new double[5])
                    .orElse(0.20);
            double putIv = ImpliedVolatilitySolver.solve(OptionType.PUT, spot, strike, timeToExpiry, riskFreeRate, dividendYield, putPrice, new double[5])
                    .orElse(0.20);

            quotes.add(new OptionQuote(symbol, expiry, strike, OptionType.CALL, Math.max(0.0, callPrice * 0.98), callPrice * 1.02, callIv, 500L, 1200L));
            quotes.add(new OptionQuote(symbol, expiry, strike, OptionType.PUT, Math.max(0.0, putPrice * 0.98), putPrice * 1.02, putIv, 400L, 1100L));
        }

        return new OptionChain(symbol, expiry, spot, quotes);
    }
}
