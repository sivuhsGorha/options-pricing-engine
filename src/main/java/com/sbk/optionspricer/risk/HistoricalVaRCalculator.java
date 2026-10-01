package com.sbk.optionspricer.risk;

import java.util.Arrays;

/**
 * Historical Value-at-Risk (VaR) Engine.
 * Computes the 99% confidence VaR using a historical simulation approach.
 *
 * Conventions:
 * - PnL vector represents daily dollar returns (positive = gain, negative = loss).
 * - Loss is defined as -PnL.
 * - 99% VaR represents the 99th percentile loss (the 1st percentile of sorted PnL).
 * - If the 99th percentile loss is non-positive (all profitable or non-negative PnL),
 *   reported VaR loss is 0.0.
 * - Requires a minimum sample size of 10 returns.
 */
public class HistoricalVaRCalculator {

    private static final int MIN_SAMPLE_SIZE = 10;

    /**
     * Calculates the 99% Historical VaR loss.
     *
     * @param historicalPnL Array of daily PnL vectors (or simulated returns).
     * @return The 99th percentile worst-case positive loss (0.0 if no loss is incurred).
     */
    public static double calculate99PercentVaR(double[] historicalPnL) {
        if (historicalPnL == null || historicalPnL.length < MIN_SAMPLE_SIZE) {
            throw new IllegalArgumentException("Historical PnL sample must contain at least " + MIN_SAMPLE_SIZE + " entries");
        }

        // Clone to avoid modifying the caller's array
        double[] sortedPnL = historicalPnL.clone();
        Arrays.sort(sortedPnL);

        // 99% VaR corresponds to the 1st percentile of sorted PnL (worst 1% tail loss)
        int index99 = Math.max(0, (int) Math.floor((sortedPnL.length - 1) * 0.01));

        double pnlAt99 = sortedPnL[index99];

        // Loss = -PnL. If PnL is positive, loss is non-positive (no loss incurred at 99% confidence level).
        return (pnlAt99 < 0) ? -pnlAt99 : 0.0;
    }
}
