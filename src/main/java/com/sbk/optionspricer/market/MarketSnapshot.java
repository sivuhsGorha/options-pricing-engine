package com.sbk.optionspricer.market;

import java.time.Instant;

/**
 * Normalized, provider-agnostic market snapshot used by live feeds and simulation/fallback modes.
 *
 * <p>A field the provider did not supply is <em>unknown</em>, never a placeholder: a source without a book
 * (Finnhub, Alpha Vantage, Marketstack) has {@code NaN} bid and ask, and a source without a volume figure
 * has {@link #VOLUME_UNKNOWN}. Consumers check {@link #hasBook()} and {@link #hasVolume()} and must not
 * trade, risk-check or display an invented number in their place. (Earlier versions filled these with a
 * +/- 1 cent spread and a volume of 2000, which the liquidity check then treated as real.)
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
    /** Volume the provider did not report. */
    public static final long VOLUME_UNKNOWN = -1L;

    public MarketSnapshot {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        boolean noBook = Double.isNaN(bid) && Double.isNaN(ask);
        if (!noBook) {
            if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0.0 || ask <= 0.0) {
                throw new IllegalArgumentException("bid and ask must both be positive finite values, or both NaN for no book");
            }
            if (bid >= ask) {
                throw new IllegalArgumentException("bid must be lower than ask");
            }
        }
        if (!Double.isFinite(last) || last <= 0.0) {
            throw new IllegalArgumentException("last must be a positive finite value");
        }
        if (volume < 0L && volume != VOLUME_UNKNOWN) {
            throw new IllegalArgumentException("volume must be non-negative or VOLUME_UNKNOWN");
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

    /** True when the provider supplied a bid and an ask. */
    public boolean hasBook() {
        return !Double.isNaN(bid);
    }

    /** True when the provider supplied a volume. */
    public boolean hasVolume() {
        return volume != VOLUME_UNKNOWN;
    }
}
