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
    
    // Contract multiplier (e.g., 100 shares per option contract)
    private final int multiplier;

    public PortfolioPosition(String symbol, int quantity, int multiplier) {
        if (symbol == null || symbol.trim().isEmpty()) throw new IllegalArgumentException("Symbol must be valid");
        if (multiplier <= 0) throw new IllegalArgumentException("Multiplier must be positive");
        this.symbol = symbol;
        this.quantity = quantity;
        this.multiplier = multiplier;
        if (quantity != 0) {
            FillLedger.recordFill(symbol, quantity, multiplier);
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
    
    public void addQuantity(int executedQty) {
        this.quantity = Math.addExact(this.quantity, executedQty);
        FillLedger.recordFill(symbol, executedQty, multiplier);
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

    public String getSymbol() {
        return symbol;
    }

    public int getQuantity() {
        return quantity;
    }
}
