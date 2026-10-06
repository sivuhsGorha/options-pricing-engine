package com.sbk.optionspricer.risk;

import java.util.Arrays;

/**
 * Expected Shortfall (Conditional VaR) Calculator.
 * Measures the average loss over the worst (1 - c) scenarios.
 */
public class ExpectedShortfallCalculator {

    /**
     * Calculates the Expected Shortfall (CVaR) scaled to a multi-day holding period.
     *
     * @param historicalPnL Array of daily PnL vectors (or simulated returns).
     * @param confidenceLevel e.g., 0.99 for 99%
     * @param holdingPeriodDays e.g., 10 for a 10-day holding period
     * @return The expected shortfall (average loss beyond VaR).
     */
    public static double calculateExpectedShortfall(double[] historicalPnL, double confidenceLevel, int holdingPeriodDays) {
        if (historicalPnL == null || historicalPnL.length == 0) {
            throw new IllegalArgumentException("PnL array cannot be empty");
        }

        double[] sortedPnL = historicalPnL.clone();
        Arrays.sort(sortedPnL);

        double tailPercentile = 1.0 - confidenceLevel;
        int numTailEvents = Math.max(1, (int) Math.round(sortedPnL.length * tailPercentile));

        double sumTailPnL = 0.0;
        for (int i = 0; i < numTailEvents; i++) {
            sumTailPnL += sortedPnL[i];
        }

        double averageTailPnL = sumTailPnL / numTailEvents;
        double dailyShortfallLoss = (averageTailPnL < 0) ? -averageTailPnL : 0.0;

        // Scale by sqrt of time (assuming IID normal returns; common scaling approximation)
        return dailyShortfallLoss * Math.sqrt(holdingPeriodDays);
    }
}
