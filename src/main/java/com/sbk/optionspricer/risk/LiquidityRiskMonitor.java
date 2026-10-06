package com.sbk.optionspricer.risk;

/**
 * Simple liquidity guard that flags when a market is too wide or too thin to trade safely.
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

    public boolean isMarketLiquid(double bid, double ask, long volume) {
        if (volume < 0L) {
            throw new IllegalArgumentException("volume must be non-negative");
        }
        double spreadBps = calculateSpreadBps(bid, ask);
        return spreadBps <= maxSpreadBps && volume >= minimumVolume;
    }

    public double getMaxSpreadBps() {
        return maxSpreadBps;
    }

    public long getMinimumVolume() {
        return minimumVolume;
    }
}
