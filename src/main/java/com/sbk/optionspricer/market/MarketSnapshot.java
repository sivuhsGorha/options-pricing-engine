package com.sbk.optionspricer.market;

import com.sbk.optionspricer.instruments.Instrument;

import java.time.Instant;

/**
 * Normalized, provider-agnostic market snapshot used by live feeds and simulation/fallback modes.
 *
 * <p>A field the provider did not supply is <em>unknown</em>, never a placeholder: a source without a book
 * (Finnhub, Alpha Vantage, Marketstack) has {@code NaN} bid and ask, and a source without a volume figure
 * has {@link #VOLUME_UNKNOWN}. Consumers check {@link #hasBook()} and {@link #hasVolume()} and must not
 * trade, risk-check or display an invented number in their place. (Earlier versions filled these with a
 * +/- 1 cent spread and a volume of 2000, which the liquidity check then treated as real.)
 *
 * <p>For an option, {@code instrument} identifies the contract and {@code symbol} is its OCC contract symbol,
 * so positions are booked per contract with the contract's own multiplier while concentration limits apply to
 * the {@link #underlying()}. For a stock {@code instrument} is null and the symbol is the ticker.
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
        MarketDataStatus status,
        Instrument instrument
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
        if (instrument != null && !symbol.equals(instrument.contractSymbol())) {
            throw new IllegalArgumentException("an option snapshot's symbol must be its contract symbol "
                    + instrument.contractSymbol() + ", not " + symbol);
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

    /** A stock (or any non-option) snapshot. */
    public MarketSnapshot(String symbol, double bid, double ask, double last, long volume, Instant timestamp,
                          Instant sourceTimestamp, long derivedQuoteAgeMs, String source, MarketDataStatus status) {
        this(symbol, bid, ask, last, volume, timestamp, sourceTimestamp, derivedQuoteAgeMs, source, status, null);
    }

    /** True when the provider supplied a bid and an ask. */
    public boolean hasBook() {
        return !Double.isNaN(bid);
    }

    /** True when the provider supplied a volume. */
    public boolean hasVolume() {
        return volume != VOLUME_UNKNOWN;
    }

    public boolean isOption() {
        return instrument != null;
    }

    /** The ticker concentration limits apply to: the option's underlying, or the symbol itself for a stock. */
    public String underlying() {
        return instrument != null ? instrument.symbol() : symbol;
    }

    /** The contract's multiplier for an option; the given default (the configured one) for a stock. */
    public int multiplierOr(int fallback) {
        return instrument != null ? (int) Math.round(instrument.multiplier()) : fallback;
    }
}
