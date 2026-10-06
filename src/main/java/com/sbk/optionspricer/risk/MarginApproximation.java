package com.sbk.optionspricer.risk;

/**
 * Standard Portfolio Analysis of Risk (Margin) / Eurex Clearing Margin Simulator.
 * Computes the Initial Margin (IM) required by the clearinghouse by stress-testing 
 * the portfolio's Greeks against extreme market scenarios.
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
        
        // Define the 4 extreme corners of the Margin stress matrix
        double[][] scenarios = {
            { SPOT_SHOCK_UP, VOL_SHOCK_UP },
            { SPOT_SHOCK_UP, VOL_SHOCK_DOWN },
            { SPOT_SHOCK_DOWN, VOL_SHOCK_UP },
            { SPOT_SHOCK_DOWN, VOL_SHOCK_DOWN }
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
