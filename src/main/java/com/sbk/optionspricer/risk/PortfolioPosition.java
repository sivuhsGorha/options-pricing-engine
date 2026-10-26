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
    /** Per year, as the pricer reports it (divide by 365 for a day's decay). */
    private double theta;
    /** Per unit of rate (1.00 = 100 percentage points). */
    private double rho;
    private double vanna;
    private double volga;
    private double charm;
    private double speed;
    private double color;
    
    // Contract multiplier (e.g., 100 shares per option contract)
    private final int multiplier;

    public PortfolioPosition(String symbol, int quantity, int multiplier) {
        if (symbol == null || symbol.trim().isEmpty()) throw new IllegalArgumentException("Symbol must be valid");
        if (multiplier <= 0) throw new IllegalArgumentException("Multiplier must be positive");
        this.symbol = symbol;
        this.quantity = quantity;
        this.multiplier = multiplier;
    }

    /** True once something has supplied this position's Greeks: the tracker for a share, the valuation service for an option. */
    private volatile boolean valued;

    /** Delta, gamma and vega only: theta and rho are set to zero (a share has none). */
    public void updateGreeks(double newDelta, double newGamma, double newVega) {
        updateGreeks(newDelta, newGamma, newVega, 0.0, 0.0);
    }

    public void updateGreeks(double newDelta, double newGamma, double newVega, double newTheta, double newRho) {
        if (!Double.isFinite(newDelta) || !Double.isFinite(newGamma) || !Double.isFinite(newVega)
                || !Double.isFinite(newTheta) || !Double.isFinite(newRho)) {
            throw new IllegalArgumentException("Greeks must be finite");
        }
        this.delta = newDelta;
        this.gamma = newGamma;
        this.vega = newVega;
        this.theta = newTheta;
        this.rho = newRho;
        this.valued = true;
    }

    /**
     * Whether the Greeks have been supplied. A position that has not been valued contributes zero rather than a
     * guess, and the admission gate refuses to add risk on top of it.
     */
    public boolean isValued() {
        return valued;
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
    
    /** Adds a signed quantity; recording to the ledger is the tracker's job, which knows the price. */
    public void addQuantity(int executedQty) {
        this.quantity = Math.addExact(this.quantity, executedQty);
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

    public double getPositionTheta() {
        return (double) Math.multiplyExact(quantity, multiplier) * theta;
    }

    public double getPositionRho() {
        return (double) Math.multiplyExact(quantity, multiplier) * rho;
    }

    public double getTheta() {
        return theta;
    }

    public double getRho() {
        return rho;
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
