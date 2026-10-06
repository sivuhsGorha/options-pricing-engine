package com.sbk.optionspricer.market;

import com.sbk.optionspricer.web.LiveSpotProvider;

import java.time.Instant;

/**
 * Bridge between the live feed and the normalized StrategyExecutionLoop market context.
 * Falls back to a simulated quote when a live source is unavailable, while preserving the
 * original provider status for diagnostics and dashboards.
 */
public class LiveMarketSnapshotAdapter implements MarketSnapshotAdapter {
    private final LiveSpotProvider liveSpotProvider;
    private final double fallbackSpot;
    private final double spreadFraction;

    public LiveMarketSnapshotAdapter() {
        this(new LiveSpotProvider(), 100.0, 0.01d);
    }

    public LiveMarketSnapshotAdapter(LiveSpotProvider liveSpotProvider) {
        this(liveSpotProvider, 100.0, 0.01d);
    }

    public LiveMarketSnapshotAdapter(LiveSpotProvider liveSpotProvider, double spreadFraction) {
        this(liveSpotProvider, 100.0, spreadFraction);
    }

    public LiveMarketSnapshotAdapter(LiveSpotProvider liveSpotProvider, double fallbackSpot, double spreadFraction) {
        this.liveSpotProvider = liveSpotProvider == null ? new LiveSpotProvider() : liveSpotProvider;
        this.fallbackSpot = fallbackSpot > 0.0 ? fallbackSpot : 100.0;
        this.spreadFraction = spreadFraction > 0.0 ? spreadFraction : 0.01d;
    }

    @Override
    public MarketSnapshot getSnapshot(String symbol) {
        String normalizedSymbol = symbol == null || symbol.isBlank() ? "SPY" : symbol.trim().toUpperCase();
        LiveSpotProvider.Quote quote = liveSpotProvider.getQuote(normalizedSymbol);
        long now = System.currentTimeMillis();

        if (quote != null && Double.isFinite(quote.last()) && quote.last() > 0.0) {
            long ageMs = now - quote.timestamp();
            MarketDataStatus status = quote.status();
            // Demote to STALE if older than 30s for live NBBO check
            if (status == MarketDataStatus.LIVE && ageMs > 30000L) {
                status = MarketDataStatus.STALE;
            }
            return new MarketSnapshot(
                    normalizedSymbol,
                    Double.isFinite(quote.bid()) ? quote.bid() : quote.last() * (1 - spreadFraction / 2.0),
                    Double.isFinite(quote.ask()) ? quote.ask() : quote.last() * (1 + spreadFraction / 2.0),
                    quote.last(),
                    quote.volume(),
                    Instant.ofEpochMilli(now),
                    Instant.ofEpochMilli(quote.timestamp()),
                    Math.max(0, ageMs),
                    quote.source(),
                    status
            );
        }

        double simulatedMid = fallbackSpot;
        double spread = Math.max(simulatedMid * spreadFraction, 0.01d);
        MarketDataStatus status = liveSpotProvider.hasMarketDataKeys() ? MarketDataStatus.UNAVAILABLE : MarketDataStatus.SIMULATED;
        return new MarketSnapshot(
                normalizedSymbol,
                simulatedMid - (spread / 2.0d),
                simulatedMid + (spread / 2.0d),
                simulatedMid,
                2000L,
                Instant.now(),
                Instant.now(),
                0L,
                "SIMULATED",
                status
        );
    }
}
