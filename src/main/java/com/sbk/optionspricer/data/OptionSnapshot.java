package com.sbk.optionspricer.data;

import com.sbk.optionspricer.OptionType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A single historical option snapshot intended for storage and replay.
 */
public record OptionSnapshot(
        Instant timestamp,
        String symbol,
        LocalDate expiry,
        double strike,
        OptionType type,
        double spot,
        double bid,
        double ask,
        double impliedVolatility,
        long volume,
        long openInterest
) {
    public OptionSnapshot {
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (!Double.isFinite(strike) || strike <= 0.0) {
            throw new IllegalArgumentException("strike must be positive and finite");
        }
        if (!Double.isFinite(spot) || spot <= 0.0) {
            throw new IllegalArgumentException("spot must be positive and finite");
        }
        if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid < 0.0 || ask < 0.0) {
            throw new IllegalArgumentException("bid and ask must be finite and non-negative");
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
