package com.sbk.optionspricer.market;

import com.sbk.optionspricer.web.LiveSpotProvider;

import java.time.Instant;

/**
 * Bridge between the live feed and the normalized StrategyExecutionLoop market context.
 *
 * <p>A live quote is passed through as the provider gave it: a source without a book yields a snapshot
 * without a book, a source without a volume yields {@link MarketSnapshot#VOLUME_UNKNOWN}. Nothing is
 * filled in. When no live source answers, the adapter returns a SIMULATED snapshot (no keys configured) or
 * an UNAVAILABLE one (keys configured, nothing answered); both are status-gated so they are never traded by
 * default and never shown as a price.
 */
public class LiveMarketSnapshotAdapter implements MarketSnapshotAdapter {
    /** A LIVE quote whose price is older than this is STALE. */
    static final long STALE_AFTER_LIVE_MS = 30_000L;
    /**
     * A DELAYED source lags the market by design (Finnhub's free quote, end-of-day feeds), so its own timestamp
     * runs a minute or more behind during trading. It is STALE only beyond this; the order gate uses the same
     * limit ({@link com.sbk.optionspricer.execution.OrderManager.MarketDataPolicy}).
     */
    static final long STALE_AFTER_DELAYED_MS = 120_000L;
    private final LiveSpotProvider liveSpotProvider;
    private final double fallbackSpot;
    /** Spread of the SIMULATED snapshot only; live quotes keep their real book or none. */
    private final double simulatedSpreadFraction;

    public LiveMarketSnapshotAdapter() {
        this(new LiveSpotProvider(), 100.0, 0.01d);
    }

    public LiveMarketSnapshotAdapter(LiveSpotProvider liveSpotProvider) {
        this(liveSpotProvider, 100.0, 0.01d);
    }

    public LiveMarketSnapshotAdapter(LiveSpotProvider liveSpotProvider, double simulatedSpreadFraction) {
        this(liveSpotProvider, 100.0, simulatedSpreadFraction);
    }

    public LiveMarketSnapshotAdapter(LiveSpotProvider liveSpotProvider, double fallbackSpot, double simulatedSpreadFraction) {
        this.liveSpotProvider = liveSpotProvider == null ? new LiveSpotProvider() : liveSpotProvider;
        this.fallbackSpot = fallbackSpot > 0.0 ? fallbackSpot : 100.0;
        this.simulatedSpreadFraction = simulatedSpreadFraction > 0.0 ? simulatedSpreadFraction : 0.01d;
    }

    @Override
    public MarketSnapshot getSnapshot(String symbol) {
        String normalizedSymbol = symbol == null || symbol.isBlank() ? "SPY" : symbol.trim().toUpperCase();
        LiveSpotProvider.Quote quote = liveSpotProvider.getQuote(normalizedSymbol);
        long now = System.currentTimeMillis();

        if (quote != null && Double.isFinite(quote.last()) && quote.last() > 0.0) {
            long ageMs = now - quote.timestamp();
            MarketDataStatus status = quote.status();
            // Freshness is a property of the price's own timestamp, not of the provider label: a DELAYED
            // or end-of-day quote from hours ago is stale, and a quote with no timestamp (0) is as old as it gets.
            if ((status == MarketDataStatus.LIVE && ageMs > STALE_AFTER_LIVE_MS)
                    || (status == MarketDataStatus.DELAYED && ageMs > STALE_AFTER_DELAYED_MS)) {
                status = MarketDataStatus.STALE;
            }
            boolean hasBook = Double.isFinite(quote.bid()) && Double.isFinite(quote.ask()) && quote.bid() < quote.ask();
            return new MarketSnapshot(
                    normalizedSymbol,
                    hasBook ? quote.bid() : Double.NaN,
                    hasBook ? quote.ask() : Double.NaN,
                    quote.last(),
                    quote.volume() < 0L ? MarketSnapshot.VOLUME_UNKNOWN : quote.volume(),
                    Instant.ofEpochMilli(now),
                    Instant.ofEpochMilli(quote.timestamp()),
                    Math.max(0, ageMs),
                    quote.source(),
                    status
            );
        }

        double simulatedMid = fallbackSpot;
        double spread = Math.max(simulatedMid * simulatedSpreadFraction, 0.01d);
        MarketDataStatus status = liveSpotProvider.hasMarketDataKeys() ? MarketDataStatus.UNAVAILABLE : MarketDataStatus.SIMULATED;
        // SIMULATED data is current by construction. UNAVAILABLE has no source time at all: report the
        // epoch and a correspondingly huge age instead of pretending the placeholder was just observed.
        boolean unavailable = status == MarketDataStatus.UNAVAILABLE;
        return new MarketSnapshot(
                normalizedSymbol,
                simulatedMid - (spread / 2.0d),
                simulatedMid + (spread / 2.0d),
                simulatedMid,
                MarketSnapshot.VOLUME_UNKNOWN,
                Instant.now(),
                unavailable ? Instant.EPOCH : Instant.now(),
                unavailable ? now : 0L,
                "SIMULATED",
                status
        );
    }
}
