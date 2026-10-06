package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.FillLedger;
import com.sbk.optionspricer.risk.FillRecorder;
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
    private final FillRecorder fillRecorder;

    /** Live tracker: fills are recorded to the authoritative {@link FillLedger}. */
    public PositionTracker() {
        this(FillRecorder.LEDGER);
    }

    public PositionTracker(FillRecorder fillRecorder) {
        if (fillRecorder == null) {
            throw new IllegalArgumentException("fillRecorder must not be null");
        }
        this.fillRecorder = fillRecorder;
    }

    /** Tracker for backtests and simulations: never writes to the fill ledger. */
    public static PositionTracker inMemory() {
        return new PositionTracker(FillRecorder.NONE);
    }

    public synchronized void applyFill(ExecutionFill fill) {
        if (fill == null) {
            throw new IllegalArgumentException("fill must not be null");
        }

        String symbol = fill.symbol().trim().toUpperCase();
        PortfolioPosition position = positions.get(symbol);
        if (position == null) {
            position = new PortfolioPosition(symbol, 0, fill.multiplier(), fillRecorder);
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
    }

    public synchronized PortfolioPosition getPosition(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return positions.get(symbol.trim().toUpperCase());
    }

    public synchronized int getNetQuantity(String symbol) {
        PortfolioPosition position = getPosition(symbol);
        return position == null ? 0 : position.getQuantity();
    }

    public synchronized double getNotional(String symbol) {
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

    public synchronized Map<String, PortfolioPosition> getPositions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(positions));
    }

    public synchronized double getNetDelta() {
        double total = 0.0;
        for (PortfolioPosition position : positions.values()) {
            total += position.getPositionDelta();
        }
        return total;
    }

    public synchronized double getNetGamma() {
        double total = 0.0;
        for (PortfolioPosition position : positions.values()) {
            total += position.getPositionGamma();
        }
        return total;
    }

    public synchronized double getNetVega() {
        double total = 0.0;
        for (PortfolioPosition position : positions.values()) {
            total += position.getPositionVega();
        }
        return total;
    }

    public synchronized double getNetNotional() {
        double total = 0.0;
        for (Map.Entry<String, PortfolioPosition> entry : positions.entrySet()) {
            total += getNotional(entry.getKey());
        }
        return total;
    }

    public synchronized PortfolioExposure snapshotExposure() {
        return new PortfolioExposure(getNetDelta(), getNetGamma(), getNetVega(), getNetNotional());
    }
}
