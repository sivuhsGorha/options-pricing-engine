package com.sbk.optionspricer.market;

import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.instruments.Instrument;

import java.time.Instant;
import java.util.Optional;

/**
 * Turns an option-chain quote into the {@link MarketSnapshot} the order gate judges, with the same honesty
 * rules as the spot feed: a contract without a two-sided market is {@code UNAVAILABLE} (its mid is not a
 * price anyone would trade), a generated chain is {@code SIMULATED}, and a market chain is {@code DELAYED}
 * while its feed timestamp is within {@link #STALE_AFTER_MS}, {@code STALE} beyond that or when the feed
 * gave no timestamp at all.
 */
public final class OptionQuoteSnapshots {

    /** Same window as a delayed spot source: the feed's own timestamp lags the market by design. */
    public static final long STALE_AFTER_MS = 120_000L;
    /** Standard US equity and ETF option contract. */
    public static final int STANDARD_EQUITY_MULTIPLIER = 100;

    private OptionQuoteSnapshots() {
    }

    /**
     * @param marketData false for a generated chain (status SIMULATED)
     * @param asOf       the feed's timestamp, or null when the feed did not say (then the quote is STALE)
     * @return empty when the quote has no price at all (bid and ask both zero)
     */
    public static Optional<MarketSnapshot> toSnapshot(OptionQuote quote, String underlying, int multiplier, String source,
                                                      boolean marketData, Instant asOf, Instant now) {
        if (quote == null || underlying == null || now == null) {
            throw new IllegalArgumentException("quote, underlying and now must not be null");
        }
        Instrument instrument = new Instrument(underlying, quote.expiry(), quote.strike(), quote.type(), multiplier, null, 0.01, true);
        boolean twoSided = quote.bid() > 0.0 && quote.ask() > quote.bid();
        double last = twoSided ? quote.midPrice() : Math.max(quote.bid(), quote.ask());
        if (!(last > 0.0)) {
            return Optional.empty();
        }

        MarketDataStatus status;
        long ageMs;
        Instant sourceTime;
        if (!marketData) {
            status = MarketDataStatus.SIMULATED;
            ageMs = 0L;
            sourceTime = asOf == null ? now : asOf;
        } else if (asOf == null) {
            status = MarketDataStatus.STALE; // no feed time means the age is unknown, which reads as "very old"
            ageMs = now.toEpochMilli();
            sourceTime = Instant.EPOCH;
        } else {
            ageMs = Math.max(0L, now.toEpochMilli() - asOf.toEpochMilli());
            status = ageMs > STALE_AFTER_MS ? MarketDataStatus.STALE : MarketDataStatus.DELAYED;
            sourceTime = asOf;
        }
        if (!twoSided) {
            status = MarketDataStatus.UNAVAILABLE; // one-sided or crossed: nothing to trade against
        }
        return Optional.of(new MarketSnapshot(instrument.contractSymbol(),
                twoSided ? quote.bid() : Double.NaN, twoSided ? quote.ask() : Double.NaN, last,
                Math.max(0L, quote.volume()), now, sourceTime, ageMs, source, status, instrument));
    }
}
