package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.PortfolioPosition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reconciles execution fills into the portfolio book and exposes the net position
 * by symbol. This is the minimum operational bridge between accepted orders and
 * the risk/accounting layer.
 */
public class PositionTracker {

    private static volatile PositionTracker lastLiveTracker = null;

    public record PortfolioExposure(double netDelta, double netGamma, double netVega, double netNotional) {}

    public record ExecutionFill(String symbol, int quantity, int multiplier, double executionPrice) {
        public ExecutionFill {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("symbol must not be blank");
            }
            if (quantity == 0) {
                throw new IllegalArgumentException("quantity must be non-zero");
            }
            if (multiplier <= 0) {
                throw new IllegalArgumentException("multiplier must be positive");
            }
            if (!Double.isFinite(executionPrice) || executionPrice <= 0.0) {
                throw new IllegalArgumentException("executionPrice must be positive and finite");
            }
        }
    }

    private final Map<String, PortfolioPosition> positions = new LinkedHashMap<>();
    private final Map<String, Double> lastExecutionPrice = new LinkedHashMap<>();

    public PositionTracker() {
        lastLiveTracker = this;
    }

    public void applyFill(ExecutionFill fill) {
        if (fill == null) {
            throw new IllegalArgumentException("fill must not be null");
        }

        String symbol = fill.symbol().trim().toUpperCase();
        PortfolioPosition position = positions.get(symbol);
        if (position == null) {
            position = new PortfolioPosition(symbol, 0, fill.multiplier());
            // Linear underlying exposure until a pricer supplies instrument Greeks:
            // +1 delta per unit, no gamma or vega. Exposure then scales as quantity * multiplier * delta.
            position.updateGreeks(1.0, 0.0, 0.0);
            positions.put(symbol, position);
        }

        if (position.getMultiplier() != fill.multiplier()) {
            throw new IllegalArgumentException("fill multiplier does not match existing position multiplier");
        }

        position.addQuantity(fill.quantity());

        lastExecutionPrice.put(symbol, fill.executionPrice());
        lastLiveTracker = this;
    }

    public PortfolioPosition getPosition(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return positions.get(symbol.trim().toUpperCase());
    }

    public int getNetQuantity(String symbol) {
        PortfolioPosition position = getPosition(symbol);
        return position == null ? 0 : position.getQuantity();
    }

    public double getNotional(String symbol) {
        PortfolioPosition position = getPosition(symbol);
        if (position == null) {
            return 0.0;
        }
        Double lastPrice = lastExecutionPrice.get(symbol.trim().toUpperCase());
        if (lastPrice == null || !Double.isFinite(lastPrice)) {
            return 0.0;
        }
        return Math.abs((double) position.getQuantity() * position.getMultiplier() * lastPrice);
    }

    public Map<String, PortfolioPosition> getPositions() {
        return Collections.unmodifiableMap(positions);
    }

    public double getNetDelta() {
        double total = 0.0;
        for (PortfolioPosition position : positions.values()) {
            total += position.getPositionDelta();
        }
        return total;
    }

    public double getNetGamma() {
        double total = 0.0;
        for (PortfolioPosition position : positions.values()) {
            total += position.getPositionGamma();
        }
        return total;
    }

    public double getNetVega() {
        double total = 0.0;
        for (PortfolioPosition position : positions.values()) {
            total += position.getPositionVega();
        }
        return total;
    }

    public double getNetNotional() {
        double total = 0.0;
        for (Map.Entry<String, PortfolioPosition> entry : positions.entrySet()) {
            total += getNotional(entry.getKey());
        }
        return total;
    }

    public static PortfolioExposure snapshotPortfolioExposure() {
        PositionTracker tracker = lastLiveTracker;
        if (tracker == null) {
            return new PortfolioExposure(0.0, 0.0, 0.0, 0.0);
        }
        return new PortfolioExposure(tracker.getNetDelta(), tracker.getNetGamma(), tracker.getNetVega(), tracker.getNetNotional());
    }
}
