package com.sbk.optionspricer.risk;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates live Greeks across the entire portfolio in real-time.
 * Essential for Delta-hedging and ensuring the firm stays market-neutral.
 */
public class GreekAggregator {
    
    private final List<PortfolioPosition> positions = new ArrayList<>();
    
    // Firm-wide Risk Limits
    private final double maxNetDelta;
    private final double maxNetVega;

    public GreekAggregator(double maxNetDelta, double maxNetVega) {
        this.maxNetDelta = maxNetDelta;
        this.maxNetVega = maxNetVega;
    }

    public void addPosition(PortfolioPosition position) {
        positions.add(position);
    }

    public double calculateNetDelta() {
        double netDelta = 0.0;
        for (PortfolioPosition p : positions) {
            netDelta += p.getPositionDelta();
        }
        return netDelta;
    }

    public double calculateNetGamma() {
        double netGamma = 0.0;
        for (PortfolioPosition p : positions) {
            netGamma += p.getPositionGamma();
        }
        return netGamma;
    }

    public double calculateNetVega() {
        double netVega = 0.0;
        for (PortfolioPosition p : positions) {
            netVega += p.getPositionVega();
        }
        return netVega;
    }
    
    /**
     * Checks if the current aggregated portfolio breaches the firm's macro risk limits.
     */
    public boolean checkMacroLimits() {
        double netDelta = Math.abs(calculateNetDelta());
        double netVega = Math.abs(calculateNetVega());
        
        if (netDelta > maxNetDelta) {
            System.err.printf("[MACRO RISK] Net Delta %.2f exceeds firm limit of %.2f! DELTA HEDGE REQUIRED.%n", netDelta, maxNetDelta);
            return false;
        }
        
        if (netVega > maxNetVega) {
            System.err.printf("[MACRO RISK] Net Vega %.2f exceeds firm limit of %.2f! VOLATILITY EXPOSURE TOO HIGH.%n", netVega, maxNetVega);
            return false;
        }
        
        return true;
    }
}
