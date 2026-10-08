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
    /** Volume-weighted cost of the open quantity per symbol; absent when flat. */
    private final Map<String, Double> averageCost = new LinkedHashMap<>();
    /** P&L locked in by fills that reduced or flipped a position, in currency (quantity x multiplier x price difference). */
    private double realizedPnl;
    private final FillRecorder fillRecorder;

    /** In-memory tracker (nothing is recorded); the application passes a {@link FillLedger} and restores from it. */
    public PositionTracker() {
        this(FillRecorder.NONE);
    }

    /** A tracker that records to {@code ledger} with its book rebuilt from the ledger's existing fills. */
    public static PositionTracker restore(FillLedger ledger) {
        if (ledger == null) {
            throw new IllegalArgumentException("ledger must not be null");
        }
        PositionTracker tracker = new PositionTracker(ledger);
        tracker.replay(ledger.readAll());
        return tracker;
    }

    /** Books past fills without recording them again; used to rebuild the book from a ledger. */
    public synchronized void replay(java.util.List<FillLedger.Fill> fills) {
        for (FillLedger.Fill fill : fills) {
            book(new ExecutionFill(fill.symbol(), fill.quantity(), fill.multiplier(), fill.price()), false);
        }
        replayedFills += fills.size();
    }

    private int replayedFills;

    /** Number of fills rebuilt from the ledger at start. */
    public synchronized int replayedFills() {
        return replayedFills;
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
        book(fill, true);
    }

    private void book(ExecutionFill fill, boolean record) {
        String symbol = fill.symbol().trim().toUpperCase();
        PortfolioPosition position = positions.get(symbol);
        if (position != null && position.getMultiplier() != fill.multiplier()) {
            throw new IllegalArgumentException("fill multiplier does not match existing position multiplier");
        }
        if (record) {
            // Record first: if the ledger write fails the book is left unchanged, so memory never runs ahead of the record.
            fillRecorder.record(symbol, fill.quantity(), fill.multiplier(), fill.executionPrice());
        }
        if (position == null) {
            position = new PortfolioPosition(symbol, 0, fill.multiplier());
            // Linear underlying exposure until a pricer supplies instrument Greeks:
            // +1 delta per unit, no gamma or vega. Exposure then scales as quantity * multiplier * delta.
            position.updateGreeks(1.0, 0.0, 0.0);
            positions.put(symbol, position);
        }

        int before = position.getQuantity();
        position.addQuantity(fill.quantity());

        lastExecutionPrice.put(symbol, fill.executionPrice());
        bookCost(symbol, before, fill.quantity(), fill.multiplier(), fill.executionPrice());
    }

    /**
     * Average-cost accounting. Adding to a position (or opening one) blends the fill into the average; reducing
     * one realises {@code closed x multiplier x (price - average)} with the sign of the position; a fill that
     * flips the position closes the old side and opens the new one at the fill price.
     */
    private void bookCost(String symbol, int before, int filled, int multiplier, double price) {
        Double avg = averageCost.get(symbol);
        if (before == 0 || avg == null || (before > 0) == (filled > 0)) {
            double openQty = Math.abs(before);
            double blended = avg == null || openQty == 0 ? price : (openQty * avg + Math.abs(filled) * price) / (openQty + Math.abs(filled));
            averageCost.put(symbol, blended);
            return;
        }
        int closed = Math.min(Math.abs(filled), Math.abs(before));
        realizedPnl += closed * (double) multiplier * (price - avg) * Math.signum(before);
        int remaining = before + filled;
        if (remaining == 0) {
            averageCost.remove(symbol);
        } else if (Math.abs(filled) > Math.abs(before)) {
            averageCost.put(symbol, price); // flipped: the remainder was opened at this fill
        }
    }

    /** Average cost of the open quantity, or NaN when the symbol is flat or unknown. */
    public synchronized double getAverageCost(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Double.NaN;
        }
        Double avg = averageCost.get(symbol.trim().toUpperCase());
        return avg == null ? Double.NaN : avg;
    }

    public synchronized double getRealizedPnl() {
        return realizedPnl;
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
