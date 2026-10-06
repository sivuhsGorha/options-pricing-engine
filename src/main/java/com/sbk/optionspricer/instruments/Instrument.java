package com.sbk.optionspricer.instruments;

import com.sbk.optionspricer.OptionType;

import java.time.LocalDate;

/**
 * Canonical definition of a single listed option contract.
 */
public record Instrument(
        String symbol,
        LocalDate expiry,
        double strike,
        OptionType type,
        double multiplier,
        String tradingHours,
        double minTick,
        boolean active
) {
    public Instrument {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (!Double.isFinite(strike) || strike <= 0.0) {
            throw new IllegalArgumentException("strike must be positive and finite");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            throw new IllegalArgumentException("multiplier must be positive and finite");
        }
        if (!Double.isFinite(minTick) || minTick <= 0.0) {
            throw new IllegalArgumentException("minTick must be positive and finite");
        }
        if (tradingHours == null || tradingHours.isBlank()) {
            tradingHours = "09:30-16:00";
        }
    }
}
