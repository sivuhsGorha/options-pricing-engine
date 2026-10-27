package com.sbk.optionspricer.risk;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Volatility regime detector for adaptive risk management.
 * 
 * Classifies market volatility into regimes (LOW, NORMAL, HIGH, EXTREME)
 * based on recent historical volatility measurements. Used to dynamically
 * adjust Greek limits based on market conditions.
 */
public final class VolatilityRegimeDetector {

    public enum Regime {
        /** Low volatility: calm market, can use tighter limits */
        LOW(0.5),
        /** Normal volatility: baseline regime */
        NORMAL(1.0),
        /** High volatility: elevated risk, use wider limits */
        HIGH(1.5),
        /** Extreme volatility: crisis conditions, use widest limits */
        EXTREME(2.0);

        private final double limitMultiplier;

        Regime(double limitMultiplier) {
            this.limitMultiplier = limitMultiplier;
        }

        public double limitMultiplier() {
            return limitMultiplier;
        }
    }

    private final Deque<Double> volatilityHistory;
    private final int windowSize;
    private final double lowThreshold;
    private final double highThreshold;
    private final double extremeThreshold;
    private volatile Regime currentRegime = Regime.NORMAL;

    /**
     * @param windowSize Number of historical volatility points to consider
     * @param lowThreshold Volatility below this (as fraction of long-term average) is LOW regime
     * @param highThreshold Volatility above this is HIGH regime
     * @param extremeThreshold Volatility above this is EXTREME regime
     */
    public VolatilityRegimeDetector(int windowSize, double lowThreshold, double highThreshold, double extremeThreshold) {
        if (windowSize < 10) {
            throw new IllegalArgumentException("windowSize must be at least 10");
        }
        if (lowThreshold <= 0 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("lowThreshold must be positive and less than highThreshold");
        }
        if (highThreshold >= extremeThreshold) {
            throw new IllegalArgumentException("highThreshold must be less than extremeThreshold");
        }
        
        this.windowSize = windowSize;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.extremeThreshold = extremeThreshold;
        this.volatilityHistory = new ArrayDeque<>(windowSize);
    }

    /**
     * Default constructor with sensible thresholds.
     * Uses 60-minute window, thresholds at 0.5x, 1.5x, and 2.5x of baseline.
     */
    public VolatilityRegimeDetector() {
        this(60, 0.5, 1.5, 2.5);
    }

    /**
     * Adds a new volatility measurement and updates the regime.
     * 
     * @param volatility Current volatility (as annualized fraction, e.g., 0.20 for 20%)
     */
    public void addVolatility(double volatility) {
        if (!Double.isFinite(volatility) || volatility <= 0) {
            throw new IllegalArgumentException("volatility must be positive and finite");
        }

        volatilityHistory.addLast(volatility);
        
        // Maintain window size
        while (volatilityHistory.size() > windowSize) {
            volatilityHistory.removeFirst();
        }

        // Update regime
        updateRegime();
    }

    /**
     * Updates the volatility regime based on recent history.
     */
    private void updateRegime() {
        if (volatilityHistory.size() < windowSize / 2) {
            // Not enough data yet, stay in NORMAL
            currentRegime = Regime.NORMAL;
            return;
        }

        double avgVolatility = calculateAverageVolatility();
        double stdDev = calculateStdDev(avgVolatility);
        
        // Normalize current volatility by standard deviation
        double currentVol = volatilityHistory.getLast();
        double zScore = stdDev > 0 ? (currentVol - avgVolatility) / stdDev : 0;

        // Classify regime based on z-score
        if (zScore < -1.0) {
            currentRegime = Regime.LOW;
        } else if (zScore > 2.0) {
            currentRegime = Regime.EXTREME;
        } else if (zScore > 1.0) {
            currentRegime = Regime.HIGH;
        } else {
            currentRegime = Regime.NORMAL;
        }
    }

    /**
     * Calculates the average volatility over the window.
     */
    private double calculateAverageVolatility() {
        double sum = 0.0;
        for (Double vol : volatilityHistory) {
            sum += vol;
        }
        return sum / volatilityHistory.size();
    }

    /**
     * Calculates the standard deviation of volatility.
     */
    private double calculateStdDev(double mean) {
        double sumSquares = 0.0;
        for (Double vol : volatilityHistory) {
            double diff = vol - mean;
            sumSquares += diff * diff;
        }
        double variance = sumSquares / volatilityHistory.size();
        return Math.sqrt(variance);
    }

    /**
     * Returns the current volatility regime.
     */
    public Regime getCurrentRegime() {
        return currentRegime;
    }

    /**
     * Returns the limit multiplier for the current regime.
     * Use this to scale Greek limits dynamically.
     */
    public double getLimitMultiplier() {
        return currentRegime.limitMultiplier();
    }

    /**
     * Returns the adjusted limit for a given base limit.
     * 
     * @param baseLimit The base limit (e.g., max_delta from config)
     * @return The adjusted limit based on current regime
     */
    public double getAdjustedLimit(double baseLimit) {
        return baseLimit * getLimitMultiplier();
    }

    /**
     * Returns the average volatility over the window.
     */
    public double getAverageVolatility() {
        if (volatilityHistory.isEmpty()) {
            return Double.NaN;
        }
        return calculateAverageVolatility();
    }

    /**
     * Returns the number of volatility points in the window.
     */
    public int getWindowSize() {
        return volatilityHistory.size();
    }

    /**
     * Clears the volatility history.
     * Useful for resetting the detector.
     */
    public void clear() {
        volatilityHistory.clear();
        currentRegime = Regime.NORMAL;
    }
}
