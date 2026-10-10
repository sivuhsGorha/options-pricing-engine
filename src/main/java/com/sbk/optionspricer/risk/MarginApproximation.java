package com.sbk.optionspricer.risk;

/**
 * A scenario stress test on the book's net Greeks: the worst loss over a grid of spot moves (-15%, 0, +15%) and
 * volatility moves (-20, 0, +20 points), using a second-order Taylor expansion. It is a rough scale for how much
 * the book could lose in a day, not a margin requirement: it is not SPAN, not Eurex Prisma and not any
 * clearinghouse's figure, and it ignores cross-gamma, skew and term-structure moves.
 */
public class MarginApproximation {
    
    // Extreme market shock assumptions (e.g., 1-day Value at Risk limits)
    private static final double SPOT_SHOCK_UP = 0.15;    // +15% Spot move
    private static final double SPOT_SHOCK_DOWN = -0.15; // -15% Spot move
    private static final double VOL_SHOCK_UP = 0.20;     // +20% Implied Vol move
    private static final double VOL_SHOCK_DOWN = -0.20;  // -20% Implied Vol move

    /**
     * Calculates the worst-case 1-day portfolio loss, which becomes the Initial Margin requirement.
     * Uses a Taylor expansion approximation: dP = Delta * dS + 0.5 * Gamma * dS^2 + Vega * dVol
     * 
     * @param aggregator The portfolio aggregator containing net Greeks
     * @param underlyingSpot The current price of the underlying asset
     * @return The required cash collateral (Initial Margin)
     */
    public static double calculateInitialMargin(GreekAggregator aggregator, double underlyingSpot) {
        return calculateInitialMargin(aggregator.calculateNetDelta(), aggregator.calculateNetGamma(),
                aggregator.calculateNetVega(), underlyingSpot);
    }

    /** Same stress test from net Greeks directly (delta in shares, gamma per $, vega per 1.0 vol). */
    public static double calculateInitialMargin(double netDelta, double netGamma, double netVega, double underlyingSpot) {
        double maxLoss = 0.0;
        
        // The full 3 x 3 grid without its centre. The corners alone miss the book that loses on one axis only: a
        // long-gamma, short-vega book gains at every corner (the spot move pays) yet loses on a pure vol rise.
        double[][] scenarios = {
            { SPOT_SHOCK_UP, VOL_SHOCK_UP },     { SPOT_SHOCK_UP, 0.0 },     { SPOT_SHOCK_UP, VOL_SHOCK_DOWN },
            { 0.0, VOL_SHOCK_UP },                                           { 0.0, VOL_SHOCK_DOWN },
            { SPOT_SHOCK_DOWN, VOL_SHOCK_UP },   { SPOT_SHOCK_DOWN, 0.0 },   { SPOT_SHOCK_DOWN, VOL_SHOCK_DOWN }
        };
        
        for (double[] scenario : scenarios) {
            double dS = underlyingSpot * scenario[0]; // Dollar change in spot
            double dVol = scenario[1];                // Absolute change in volatility
            
            // Taylor series expansion of Portfolio Value Change
            double deltaPnl = netDelta * dS;
            double gammaPnl = 0.5 * netGamma * (dS * dS);
            double vegaPnl = netVega * dVol;
            
            double totalPnl = deltaPnl + gammaPnl + vegaPnl;
            
            // If totalPnl is negative, it's a loss. We track the worst loss.
            if (totalPnl < 0) {
                maxLoss = Math.max(maxLoss, Math.abs(totalPnl));
            }
        }
        
        // The required margin is the worst-case scenario loss
        return maxLoss;
    }
}
