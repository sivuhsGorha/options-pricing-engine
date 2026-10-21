package com.sbk.optionspricer.volatility;

import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Variance Risk Premium (VRP) signal calculator.
 *
 * The VRP is the difference between implied volatility (from option prices) and
 * realized volatility (from historical returns). A positive VRP indicates that
 * the market expects higher future volatility than historical patterns suggest.
 *
 * Features:
 * - Computes VRP as IV - realized forecast
 * - Computes VRP as a percentage of implied vol
 * - Computes Z-score of VRP (how many standard deviations from mean)
 * - Provides signal direction (buy/sell/hold based on VRP level)
 */
public final class VrpSignal {

    private final double impliedVol;
    private final double realizedForecast;
    private final double vrp;
    private final double vrpPercent;
    private final double vrpZScore;
    private final SignalDirection direction;

    public enum SignalDirection {
        /** VRP is high (market rich relative to history) - consider selling premium */
        HIGH,
        /** VRP is neutral - hold */
        NEUTRAL,
        /** VRP is low (market cheap relative to history) - consider buying premium */
        LOW
    }

    private VrpSignal(double impliedVol, double realizedForecast, double vrpZScore, SignalDirection direction) {
        this.impliedVol = impliedVol;
        this.realizedForecast = realizedForecast;
        this.vrp = impliedVol - realizedForecast;
        this.vrpPercent = impliedVol > 0 ? (vrp / impliedVol) * 100.0 : 0.0;
        this.vrpZScore = vrpZScore;
        this.direction = direction;
    }

    /**
     * Creates a VRP signal from implied vol and realized forecast.
     *
     * @param impliedVol the implied volatility (e.g., from option chain)
     * @param realizedForecast the realized volatility forecast
     * @param vrpMean the historical mean VRP for z-score calculation
     * @param vrpStd the historical standard deviation of VRP
     * @param highThreshold z-score threshold for HIGH signal (default 1.0)
     * @param lowThreshold z-score threshold for LOW signal (default -1.0)
     * @return the VRP signal
     */
    public static VrpSignal fromVolatilities(double impliedVol, double realizedForecast,
                                               double vrpMean, double vrpStd,
                                               double highThreshold, double lowThreshold) {
        if (!Double.isFinite(impliedVol) || impliedVol <= 0) {
            throw new IllegalArgumentException("impliedVol must be positive and finite");
        }
        if (!Double.isFinite(realizedForecast) || realizedForecast <= 0) {
            throw new IllegalArgumentException("realizedForecast must be positive and finite");
        }

        double vrp = impliedVol - realizedForecast;
        double vrpZScore = vrpStd > 0 ? (vrp - vrpMean) / vrpStd : 0.0;

        SignalDirection direction;
        if (vrpZScore >= highThreshold) {
            direction = SignalDirection.HIGH;
        } else if (vrpZScore <= lowThreshold) {
            direction = SignalDirection.LOW;
        } else {
            direction = SignalDirection.NEUTRAL;
        }

        return new VrpSignal(impliedVol, realizedForecast, vrpZScore, direction);
    }

    /**
     * Creates a VRP signal with default thresholds (±1.0 z-score).
     */
    public static VrpSignal fromVolatilities(double impliedVol, double realizedForecast,
                                               double vrpMean, double vrpStd) {
        return fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd, 1.0, -1.0);
    }

    /**
     * Gets the implied volatility.
     */
    public double impliedVol() {
        return impliedVol;
    }

    /**
     * Gets the realized volatility forecast.
     */
    public double realizedForecast() {
        return realizedForecast;
    }

    /**
     * Gets the raw VRP (IV - realized).
     */
    public double vrp() {
        return vrp;
    }

    /**
     * Gets the VRP as a percentage of implied vol.
     */
    public double vrpPercent() {
        return vrpPercent;
    }

    /**
     * Gets the VRP z-score (how many standard deviations from historical mean).
     */
    public double vrpZScore() {
        return vrpZScore;
    }

    /**
     * Gets the signal direction.
     */
    public SignalDirection direction() {
        return direction;
    }

    /**
     * Returns a human-readable summary.
     */
    public String summary() {
        return String.format("VRP: %.4f (%.1f%% of IV, z=%.2f, direction=%s)",
                vrp, vrpPercent, vrpZScore, direction);
    }

    /**
     * Returns true if the signal suggests selling premium (HIGH VRP).
     */
    public boolean shouldSell() {
        return direction == SignalDirection.HIGH;
    }

    /**
     * Returns true if the signal suggests buying premium (LOW VRP).
     */
    public boolean shouldBuy() {
        return direction == SignalDirection.LOW;
    }

    /**
     * Returns true if the signal is neutral.
     */
    public boolean isNeutral() {
        return direction == SignalDirection.NEUTRAL;
    }
}
