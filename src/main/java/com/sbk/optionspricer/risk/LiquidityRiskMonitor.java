package com.sbk.optionspricer.risk;

/**
 * Liquidity guard: flags a market that is too wide (spread in basis points) or too thin (volume) to trade.
 *
 * <p>The two tests are separable so a caller can judge only the fields its data source supplies: a provider
 * without a book is checked on volume alone, one without volume on spread alone. Absence of data is not
 * evidence of an illiquid market and is not treated as a failure here; it is the caller's decision.
 */
public class LiquidityRiskMonitor {
    private final double maxSpreadBps;
    private final long minimumVolume;

    public LiquidityRiskMonitor(double maxSpreadBps, long minimumVolume) {
        if (!Double.isFinite(maxSpreadBps) || maxSpreadBps <= 0.0) {
            throw new IllegalArgumentException("maxSpreadBps must be finite and positive");
        }
        if (minimumVolume <= 0L) {
            throw new IllegalArgumentException("minimumVolume must be positive");
        }
        this.maxSpreadBps = maxSpreadBps;
        this.minimumVolume = minimumVolume;
    }

    public double calculateSpreadBps(double bid, double ask) {
        if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0.0 || ask <= bid) {
            throw new IllegalArgumentException("bid and ask must be finite and ask > bid > 0");
        }
        double mid = (bid + ask) / 2.0;
        return ((ask - bid) / mid) * 10_000.0;
    }

    /** Spread test alone; requires a real book. */
    public boolean isSpreadAcceptable(double bid, double ask) {
        return calculateSpreadBps(bid, ask) <= maxSpreadBps;
    }

    /** Volume test alone; requires a known, non-negative volume. */
    public boolean isVolumeAcceptable(long volume) {
        if (volume < 0L) {
            throw new IllegalArgumentException("volume must be non-negative");
        }
        return volume >= minimumVolume;
    }

    /** Both tests; requires a real book and a known volume. */
    public boolean isMarketLiquid(double bid, double ask, long volume) {
        return isSpreadAcceptable(bid, ask) && isVolumeAcceptable(volume);
    }

    public double getMaxSpreadBps() {
        return maxSpreadBps;
    }

    public long getMinimumVolume() {
        return minimumVolume;
    }
}
