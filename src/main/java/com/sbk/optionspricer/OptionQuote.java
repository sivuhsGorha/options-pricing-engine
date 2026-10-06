package com.sbk.optionspricer;

import java.time.LocalDate;

/**
 * A single option quote in a chain. The bid/ask midpoint is the canonical price used
 * for implied volatilities unless the market provides a single last-traded price.
 */
public record OptionQuote(
        String symbol,
        LocalDate expiry,
        double strike,
        OptionType type,
        double bid,
        double ask,
        double impliedVolatility,
        long volume,
        long openInterest
) {
    public OptionQuote {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (!Double.isFinite(strike) || strike <= 0.0) {
            throw new IllegalArgumentException("strike must be finite and positive");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid < 0.0 || ask < 0.0) {
            throw new IllegalArgumentException("bid and ask must be finite, non-negative");
        }
        if (!Double.isFinite(impliedVolatility) || impliedVolatility < 0.0) {
            throw new IllegalArgumentException("impliedVolatility must be finite and non-negative");
        }
    }

    public double midPrice() {
        return (bid + ask) / 2.0;
    }

    public double spread() {
        return ask - bid;
    }
}
