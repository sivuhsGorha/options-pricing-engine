package com.sbk.optionspricer.risk;

/**
 * Event-Driven Backtester simulating Level 3 (L3) order book dynamics.
 * Crucially implements the Almgren-Chriss market impact model to estimate 
 * how our own massive trades move the market against us (slippage).
 */
public class EventDrivenBacktester {

    /**
     * Calculates the permanent and temporary market impact (slippage) of a large order 
     * using a simplified Almgren-Chriss execution formula.
     * 
     * @param orderSize The quantity we are trying to trade
     * @param averageDailyVolume (ADV) The total daily volume of the instrument
     * @param dailyVolatility The daily volatility of the asset
     * @param executionTimeRatio The fraction of the day over which the trade is executed (e.g., 0.1 for 10%)
     * @return The estimated price slippage in dollars/ticks
     */
    public static double calculateAlmgrenChrissImpact(int orderSize, int averageDailyVolume, double dailyVolatility, double executionTimeRatio) {
        // Permanent Impact (Linear to size)
        // Gamma factor models information leakage (e.g., 0.1)
        double permanentImpact = 0.1 * dailyVolatility * ((double) orderSize / averageDailyVolume);
        
        // Temporary Impact (Square root of execution speed)
        // Eta factor models liquidity depletion (e.g., 0.05)
        double temporaryImpact = 0.05 * dailyVolatility * Math.sqrt((double) orderSize / (averageDailyVolume * executionTimeRatio));
        
        return permanentImpact + temporaryImpact;
    }
    
    /**
     * Simulates our position in the L3 exchange queue.
     * We don't instantly get filled; we must wait for orders ahead of us to execute.
     */
    public static int simulateQueuePosition(int orderSize, int existingQueueSize) {
        // In a true L3 backtest, we track exactly how many contracts are ahead of us
        // As trades hit the tape, this number decrements.
        System.out.printf(java.util.Locale.ROOT, "[BACKTEST] Placed %d contracts at back of queue. %d contracts ahead of us.%n", orderSize, existingQueueSize);
        return existingQueueSize + orderSize;
    }
}
