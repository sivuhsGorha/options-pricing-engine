package com.sbk.optionspricer;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple in-memory provider used for tests and fallback scenarios. It generates a synthetic Black-Scholes
 * chain around a fixed spot for the requested expiry. Nothing here is market data.
 *
 * <p>Time to expiry comes from the requested expiry date (ACT/365, see {@link TimeConventions}); the implied
 * volatility of every quote is the volatility the chain was built with, since that is what generated the prices.
 */
public final class SyntheticOptionChainProvider implements OptionChainProvider {
    private final double spot;
    private final double volatility;
    private final double riskFreeRate;
    private final double dividendYield;
    private final Clock clock;

    public SyntheticOptionChainProvider() {
        this(100.0, 0.2, 0.05, 0.0);
    }

    public SyntheticOptionChainProvider(double spot, double volatility, double riskFreeRate, double dividendYield) {
        this(spot, volatility, riskFreeRate, dividendYield, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today". */
    SyntheticOptionChainProvider(double spot, double volatility, double riskFreeRate, double dividendYield, Clock clock) {
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
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return "SYNTHETIC";
    }

    /** Generated prices are not market data; a surface fitted to them is a demonstration. */
    @Override
    public boolean isMarketData() {
        return false;
    }

    /** Generated at the moment of the request, so its as-of time is "now" on the provider's clock. */
    @Override
    public SourcedChain getSourcedChain(String symbol, LocalDate expiry) {
        return new SourcedChain(getOptionChain(symbol, expiry), sourceName(), false, List.of(), clock.instant());
    }

    @Override
    public OptionChain getOptionChain(String symbol, LocalDate expiry) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        double timeToExpiry = TimeConventions.yearFraction(LocalDate.now(clock), expiry);
        if (!(timeToExpiry > 0.0)) {
            throw new IllegalArgumentException("expiry must be after today: " + expiry);
        }

        List<OptionQuote> quotes = new ArrayList<>();
        double[] strikes = {80.0, 90.0, 95.0, 100.0, 105.0, 110.0, 120.0};
        for (double strike : strikes) {
            double callPrice = BlackScholesPricer.price(OptionType.CALL, spot, strike, timeToExpiry, riskFreeRate, volatility, dividendYield);
            double putPrice = BlackScholesPricer.price(OptionType.PUT, spot, strike, timeToExpiry, riskFreeRate, volatility, dividendYield);

            quotes.add(new OptionQuote(symbol, expiry, strike, OptionType.CALL, Math.max(0.0, callPrice * 0.98), callPrice * 1.02, volatility, 500L, 1200L));
            quotes.add(new OptionQuote(symbol, expiry, strike, OptionType.PUT, Math.max(0.0, putPrice * 0.98), putPrice * 1.02, volatility, 400L, 1100L));
        }

        return new OptionChain(symbol, expiry, spot, quotes);
    }
}
