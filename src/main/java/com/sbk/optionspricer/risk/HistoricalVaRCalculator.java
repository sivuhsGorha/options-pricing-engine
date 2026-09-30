package com.sbk.optionspricer.risk;

import java.util.Arrays;

/**
 * Historical Value-at-Risk (VaR) Engine.
 * Computes the 99% confidence VaR using a historical simulation approach.
 */
public class HistoricalVaRCalculator {
    
    /**
     * Calculates the 99% Historical VaR.
     * 
     * @param historicalPnL Array of daily PnL vectors (or simulated returns).
     * @return The 99th percentile worst-case loss.
     */
    public static double calculate99PercentVaR(double[] historicalPnL) {
        if (historicalPnL == null || historicalPnL.length == 0) {
            return 0.0;
        }
        
        // Clone to avoid modifying the original array
        double[] sortedPnL = historicalPnL.clone();
        Arrays.sort(sortedPnL);
        
        // The 99% VaR is the 1st percentile of the sorted PnL (meaning the 99% worst case)
        // E.g., out of 100 days, it's the 1st worst day.
        int index99 = (int) Math.floor(sortedPnL.length * 0.01);
        
        // Return the absolute value of the loss
        return Math.abs(sortedPnL[index99]);
    }
}
