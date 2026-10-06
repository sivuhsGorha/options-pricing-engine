package com.sbk.optionspricer.market;

/**
 * Provider-neutral interface for market snapshots consumed by risk and execution loops.
 */
public interface MarketSnapshotAdapter {
    MarketSnapshot getSnapshot(String symbol);
}
