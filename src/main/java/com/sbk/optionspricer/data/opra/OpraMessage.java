package com.sbk.optionspricer.data.opra;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.OccSymbol;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Normalized representation of an OPRA Best-Bid-Offer (BBO) quote or trade message.
 */
public record OpraMessage(
        String symbol,
        LocalDate expiry,
        double strike,
        OptionType type,
        double bid,
        long bidSize,
        double ask,
        long askSize,
        long feedTimestampNanos
) {
    public String occSymbol() {
        return OccSymbol.format(symbol, expiry, type, strike);
    }

    public double midPrice() {
        return (bid > 0.0 && ask >= bid) ? (bid + ask) / 2.0 : (bid > 0.0 ? bid : ask);
    }

    public boolean hasBook() {
        return bid > 0.0 && ask > bid;
    }

    public Instant instant() {
        return Instant.ofEpochMilli(feedTimestampNanos / 1_000_000L);
    }
}
