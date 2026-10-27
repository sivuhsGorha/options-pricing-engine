package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketSnapshot;

import java.util.Optional;

/**
 * Automatic delta hedging with configurable bands.
 * 
 * Generates hedge orders when the portfolio's net delta exceeds configured thresholds.
 * Supports multiple hedging modes: IMMEDIATE, THRESHOLD, and ADAPTIVE.
 */
public final class AutoHedger {

    public enum HedgingMode {
        /** Hedge immediately when delta exceeds the band. */
        IMMEDIATE,
        /** Hedge only after delta has been outside the band for a minimum duration. */
        THRESHOLD,
        /** Hedge based on volatility regime (dynamic bands). */
        ADAPTIVE
    }

    private final double hedgeBand;
    private final HedgingMode mode;
    private final OrderManager orderManager;
    private final PositionTracker positionTracker;
    private final double minHedgeSize;
    private final double hedgeDurationSeconds;
    private long deltaExceededTimestamp = 0;
    private boolean deltaCurrentlyExceeded = false;

    /**
     * @param hedgeBand Hedge when |net delta| exceeds this value
     * @param mode Hedging mode (IMMEDIATE, THRESHOLD, or ADAPTIVE)
     * @param orderManager Order manager for submitting hedge orders
     * @param positionTracker Position tracker for reading current exposure
     * @param minHedgeSize Minimum hedge size in shares (prevents tiny hedges)
     * @param hedgeDurationSeconds Minimum duration delta must be exceeded before hedging (THRESHOLD mode only)
     */
    public AutoHedger(double hedgeBand, HedgingMode mode, OrderManager orderManager,
                       PositionTracker positionTracker, double minHedgeSize, double hedgeDurationSeconds) {
        if (hedgeBand <= 0) {
            throw new IllegalArgumentException("hedgeBand must be positive");
        }
        if (minHedgeSize <= 0) {
            throw new IllegalArgumentException("minHedgeSize must be positive");
        }
        if (hedgeDurationSeconds < 0) {
            throw new IllegalArgumentException("hedgeDurationSeconds must be non-negative");
        }
        
        this.hedgeBand = hedgeBand;
        this.mode = mode;
        this.orderManager = orderManager;
        this.positionTracker = positionTracker;
        this.minHedgeSize = minHedgeSize;
        this.hedgeDurationSeconds = hedgeDurationSeconds * 1000; // Convert to milliseconds
    }

    /**
     * Evaluates whether a hedge order should be generated.
     * 
     * @param spot Current spot price
     * @return Optional containing the hedge order, or empty if no hedge is needed
     */
    public Optional<Order> evaluateHedge(MarketSnapshot spot) {
        PositionTracker.PortfolioExposure exposure = positionTracker.snapshotExposure();
        double netDelta = exposure.netDelta();
        double deltaMagnitude = Math.abs(netDelta);

        // Check if delta exceeds the band
        if (deltaMagnitude < hedgeBand) {
            deltaCurrentlyExceeded = false;
            deltaExceededTimestamp = 0;
            return Optional.empty();
        }

        // Mark that delta is currently exceeded
        if (!deltaCurrentlyExceeded) {
            deltaCurrentlyExceeded = true;
            deltaExceededTimestamp = System.currentTimeMillis();
        }

        // Apply hedging mode logic
        switch (mode) {
            case IMMEDIATE:
                return generateHedgeOrder(netDelta, spot);
                
            case THRESHOLD:
                long exceededDuration = System.currentTimeMillis() - deltaExceededTimestamp;
                if (exceededDuration >= hedgeDurationSeconds) {
                    return generateHedgeOrder(netDelta, spot);
                }
                return Optional.empty();
                
            case ADAPTIVE:
                // TODO: Implement volatility-regime-based adaptive hedging
                // For now, fall back to immediate
                return generateHedgeOrder(netDelta, spot);
                
            default:
                return Optional.empty();
        }
    }

    /**
     * Generates a hedge order to flatten delta.
     * 
     * @param netDelta Current net delta
     * @param spot Current spot price
     * @return Optional containing the hedge order
     */
    private Optional<Order> generateHedgeOrder(double netDelta, MarketSnapshot spot) {
        // Calculate hedge size (opposite of net delta)
        double hedgeSize = -netDelta;
        
        // Round to minimum hedge size
        if (Math.abs(hedgeSize) < minHedgeSize) {
            return Optional.empty();
        }
        
        // Round to nearest whole share
        int roundedSize = (int) Math.round(hedgeSize);
        if (roundedSize == 0) {
            return Optional.empty();
        }

        // Determine order side (isBuy = true for BUY, false for SELL)
        boolean isBuy = roundedSize > 0;
        int quantity = Math.abs(roundedSize);

        // Determine limit price (use current bid/ask if available, otherwise last)
        double limitPrice;
        if (spot.hasBook()) {
            limitPrice = isBuy ? spot.ask() : spot.bid();
        } else {
            limitPrice = spot.last();
        }

        // Create order (instrumentId is 0 for stocks, symbol is handled by OrderManager)
        Order order = new Order(
            0,  // instrumentId (0 for stocks)
            isBuy,
            quantity,
            limitPrice
        );

        return Optional.of(order);
    }

    /**
     * Manually trigger a hedge regardless of the current delta.
     * Useful for operator-initiated hedges.
     * 
     * @param spot Current spot price
     * @return Optional containing the hedge order
     */
    public Optional<Order> forceHedge(MarketSnapshot spot) {
        PositionTracker.PortfolioExposure exposure = positionTracker.snapshotExposure();
        double netDelta = exposure.netDelta();
        return generateHedgeOrder(netDelta, spot);
    }

    /**
     * Returns the current hedge band.
     */
    public double getHedgeBand() {
        return hedgeBand;
    }

    /**
     * Returns the current hedging mode.
     */
    public HedgingMode getMode() {
        return mode;
    }

    /**
     * Returns true if delta is currently exceeding the band.
     */
    public boolean isDeltaExceeded() {
        return deltaCurrentlyExceeded;
    }

    /**
     * Returns how long delta has been exceeded (in milliseconds).
     */
    public long getDeltaExceededDurationMs() {
        if (!deltaCurrentlyExceeded) {
            return 0;
        }
        return System.currentTimeMillis() - deltaExceededTimestamp;
    }
}
