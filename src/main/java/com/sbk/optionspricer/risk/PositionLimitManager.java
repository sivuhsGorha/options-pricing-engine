package com.sbk.optionspricer.risk;

/**
 * Simple per-position exposure checker for the risk engine.
 */
public class PositionLimitManager {
    private final double maxNotionalPerPosition;
    private final int maxContractsPerSymbol;

    public PositionLimitManager(double maxNotionalPerPosition, int maxContractsPerSymbol) {
        if (maxNotionalPerPosition <= 0.0 || maxContractsPerSymbol <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.maxNotionalPerPosition = maxNotionalPerPosition;
        this.maxContractsPerSymbol = maxContractsPerSymbol;
    }

    public boolean isWithinLimit(PortfolioPosition position, double referencePrice) {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        if (!Double.isFinite(referencePrice) || referencePrice <= 0.0) {
            throw new IllegalArgumentException("referencePrice must be positive and finite");
        }
        double notional = Math.abs(position.getQuantity()) * position.getMultiplier() * referencePrice;
        return notional <= maxNotionalPerPosition && Math.abs(position.getQuantity()) <= maxContractsPerSymbol;
    }
}
