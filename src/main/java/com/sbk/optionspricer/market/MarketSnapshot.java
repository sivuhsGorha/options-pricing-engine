package com.sbk.optionspricer.market;

import java.time.Instant;

/**
 * Normalized, provider-agnostic market snapshot that can be used by both live
 * feeds and simulation/fallback modes.
 */
public record MarketSnapshot(
        String symbol,
        double bid,
        double ask,
        double last,
        long volume,
        Instant timestamp,
        Instant sourceTimestamp,
        long derivedQuoteAgeMs,
        String source,
        MarketDataStatus status
) {
    public MarketSnapshot {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0.0 || ask <= 0.0) {
            throw new IllegalArgumentException("bid and ask must be positive finite values");
        }
        if (!Double.isFinite(last) || last <= 0.0) {
            throw new IllegalArgumentException("last must be a positive finite value");
        }
        if (bid >= ask) {
            throw new IllegalArgumentException("bid must be lower than ask");
        }
        if (volume < 0L) {
            throw new IllegalArgumentException("volume must be non-negative");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (sourceTimestamp == null) {
            sourceTimestamp = Instant.now();
        }
        if (status == null) {
            status = MarketDataStatus.LIVE;
        }
        if (source == null || source.isBlank()) {
            source = "UNKNOWN";
        }
    }
}
