package com.sbk.optionspricer.risk;

/**
 * Represents a live trading position.
 * Tracks the quantity held and the real-time Greek risk profile.
 */
public class PortfolioPosition {
    private final String symbol;
    private int quantity;
    
    // Per-contract Greeks computed by the pricer (Black-Scholes/PDE/Tree)
    private double delta;
    private double gamma;
    private double vega;
    private double vanna;
    private double volga;
    private double charm;
    private double speed;
    private double color;
    
    // Contract multiplier (e.g., 100 shares per option contract)
    private final int multiplier;
    private final FillRecorder fillRecorder;

    public PortfolioPosition(String symbol, int quantity, int multiplier) {
        this(symbol, quantity, multiplier, FillRecorder.LEDGER);
    }

    public PortfolioPosition(String symbol, int quantity, int multiplier, FillRecorder fillRecorder) {
        if (fillRecorder == null) throw new IllegalArgumentException("fillRecorder must not be null");
        if (symbol == null || symbol.trim().isEmpty()) throw new IllegalArgumentException("Symbol must be valid");
        if (multiplier <= 0) throw new IllegalArgumentException("Multiplier must be positive");
        this.symbol = symbol;
        this.quantity = quantity;
        this.multiplier = multiplier;
        this.fillRecorder = fillRecorder;
        if (quantity != 0) {
            fillRecorder.record(symbol, quantity, multiplier);
        }
    }

    public void updateGreeks(double newDelta, double newGamma, double newVega) {
        if (!Double.isFinite(newDelta) || !Double.isFinite(newGamma) || !Double.isFinite(newVega)) {
            throw new IllegalArgumentException("Greeks must be finite");
        }
        this.delta = newDelta;
        this.gamma = newGamma;
        this.vega = newVega;
    }

    public void updateHigherOrderGreeks(double newVanna, double newVolga, double newCharm, double newSpeed, double newColor) {
        if (!Double.isFinite(newVanna) || !Double.isFinite(newVolga)
                || !Double.isFinite(newCharm) || !Double.isFinite(newSpeed) || !Double.isFinite(newColor)) {
            throw new IllegalArgumentException("Higher-order Greeks must be finite");
        }
        this.vanna = newVanna;
        this.volga = newVolga;
        this.charm = newCharm;
        this.speed = newSpeed;
        this.color = newColor;
    }
    
    public void addQuantity(int executedQty) {
        // Record first: if the ledger write fails the in-memory position is left unchanged,
        // so memory never runs ahead of the authoritative record.
        int updated = Math.addExact(this.quantity, executedQty);
        fillRecorder.record(symbol, executedQty, multiplier);
        this.quantity = updated;
    }

    // --- Risk Exposure Calculations (Quantity * Multiplier * Greek) ---

    public double getPositionDelta() {
        return (double) Math.multiplyExact(quantity, multiplier) * delta;
    }

    public double getPositionGamma() {
        return (double) Math.multiplyExact(quantity, multiplier) * gamma;
    }

    public double getPositionVega() {
        return (double) Math.multiplyExact(quantity, multiplier) * vega;
    }

    public double getDelta() {
        return delta;
    }

    public double getGamma() {
        return gamma;
    }

    public double getVega() {
        return vega;
    }

    public double getVanna() {
        return vanna;
    }

    public double getVolga() {
        return volga;
    }

    public double getCharm() {
        return charm;
    }

    public double getSpeed() {
        return speed;
    }

    public double getColor() {
        return color;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getMultiplier() {
        return multiplier;
    }

    public int getQuantity() {
        return quantity;
    }
}
