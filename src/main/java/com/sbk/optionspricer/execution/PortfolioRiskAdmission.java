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

    /** Multiplier for a symbol with no position yet; matches the multiplier OrderManager books fills with. */
    static final int DEFAULT_MULTIPLIER = 1;

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
        return canAdmitOrder(symbol, quantity, price, tracker, DEFAULT_MULTIPLIER);
    }

    /** @param newPositionMultiplier multiplier assumed for a symbol that has no position yet (an existing position keeps its own) */
    public boolean canAdmitOrder(String symbol, int quantity, double price, PositionTracker tracker, int newPositionMultiplier) {
        if (newPositionMultiplier < 1) {
            throw new IllegalArgumentException("multiplier must be at least 1");
        }
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
        int multiplier = existing == null ? newPositionMultiplier : existing.getMultiplier();
        double notional = Math.abs((double) proposedNet * multiplier * price);

        if (notional > maxNotional) {
            return false;
        }
        if (Math.abs(proposedNet) > maxPositionAbs) {
            return false;
        }

        // Post-trade Greek exposure of the whole book. A new symbol is linear underlying exposure
        // (delta 1, no gamma/vega), matching PositionTracker.
        double unitDelta = existing == null ? 1.0 : existing.getDelta();
        double unitGamma = existing == null ? 0.0 : existing.getGamma();
        double unitVega = existing == null ? 0.0 : existing.getVega();
        double scale = (double) proposedNet * multiplier;

        double currentPositionDelta = existing == null ? 0.0 : existing.getPositionDelta();
        double currentPositionGamma = existing == null ? 0.0 : existing.getPositionGamma();
        double currentPositionVega = existing == null ? 0.0 : existing.getPositionVega();

        double currentDelta = tracker.getNetDelta();
        double currentGamma = tracker.getNetGamma();
        double currentVega = tracker.getNetVega();

        double projectedDelta = currentDelta - currentPositionDelta + scale * unitDelta;
        double projectedGamma = currentGamma - currentPositionGamma + scale * unitGamma;
        double projectedVega = currentVega - currentPositionVega + scale * unitVega;

        return !breaches(projectedDelta, currentDelta, maxDelta)
                && !breaches(projectedGamma, currentGamma, maxGamma)
                && !breaches(projectedVega, currentVega, maxVega);
    }

    /** A limit is breached only if the projected exposure is over the limit and not a reduction of current exposure. */
    private static boolean breaches(double projected, double current, double limit) {
        double projectedAbs = Math.abs(projected);
        return projectedAbs > limit && projectedAbs > Math.abs(current);
    }
}
