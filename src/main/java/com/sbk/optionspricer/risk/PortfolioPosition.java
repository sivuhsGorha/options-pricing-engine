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
        this.symbol = symbol;
        this.quantity = quantity;
        this.multiplier = multiplier;
    }

    public void updateGreeks(double newDelta, double newGamma, double newVega) {
        this.delta = newDelta;
        this.gamma = newGamma;
        this.vega = newVega;
    }
    
    public void addQuantity(int executedQty) {
        this.quantity += executedQty;
    }

    // --- Risk Exposure Calculations (Quantity * Multiplier * Greek) ---

    public double getPositionDelta() {
        return quantity * multiplier * delta;
    }

    public double getPositionGamma() {
        return quantity * multiplier * gamma;
    }

    public double getPositionVega() {
        return quantity * multiplier * vega;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getQuantity() {
        return quantity;
    }
}
