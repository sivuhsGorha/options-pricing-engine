package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.PortfolioPosition;

/**
 * Pre-trade portfolio admission guard. Ensures a new order does not push the
 * existing position book beyond defined delta, gamma, vega, or notional limits.
 */
public class PortfolioRiskAdmission {
    private final double maxNotional;
    private final double maxDelta;
    private final double maxGamma;
    private final double maxVega;
    private final double maxPositionAbs;

    public PortfolioRiskAdmission(double maxNotional, double maxDelta, double maxGamma, double maxVega, double maxPositionAbs) {
        if (maxNotional <= 0.0 || maxDelta <= 0.0 || maxGamma <= 0.0 || maxVega <= 0.0 || maxPositionAbs <= 0.0) {
            throw new IllegalArgumentException("all limits must be positive");
        }
        this.maxNotional = maxNotional;
        this.maxDelta = maxDelta;
        this.maxGamma = maxGamma;
        this.maxVega = maxVega;
        this.maxPositionAbs = maxPositionAbs;
    }

    public boolean canAdmitOrder(String symbol, int quantity, double price, PositionTracker tracker) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (tracker == null) {
            throw new IllegalArgumentException("tracker must not be null");
        }
        if (quantity == 0) {
            return false;
        }
        if (!Double.isFinite(price) || price <= 0.0) {
            throw new IllegalArgumentException("price must be positive and finite");
        }

        PortfolioPosition existing = tracker.getPosition(symbol);
        int proposedNet = (existing == null ? 0 : existing.getQuantity()) + quantity;
        double notional = Math.abs((double) proposedNet * 100.0 * price);

        if (notional > maxNotional) {
            return false;
        }
        if (Math.abs(proposedNet) > maxPositionAbs) {
            return false;
        }

        if (existing != null) {
            double deltaImpact = Math.abs(existing.getDelta() * quantity);
            double gammaImpact = Math.abs(existing.getGamma() * quantity);
            double vegaImpact = Math.abs(existing.getVega() * quantity);

            if (deltaImpact > maxDelta || gammaImpact > maxGamma || vegaImpact > maxVega) {
                return false;
            }
        }

        return true;
    }
}
