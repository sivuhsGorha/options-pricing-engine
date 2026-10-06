package com.sbk.optionspricer.rates;

import java.util.Arrays;

/**
 * A bootstrapped yield curve providing discount factors and continuously compounded zero rates,
 * using log-linear interpolation on discount factors (piecewise-constant forward rates).
 *
 * <p>Extrapolation: before the first pillar the zero rate is held flat; after the last pillar the
 * forward rate of the last interval is held flat (a single-pillar curve holds its zero rate flat,
 * since it has no interval). The curve is immutable and validated: pillar times must be positive and
 * strictly increasing, and discount factors positive and finite (above 1 is allowed for negative rates).
 */
public class YieldCurve {

    private final double[] times;
    private final double[] discountFactors;

    public YieldCurve(double[] times, double[] discountFactors) {
        if (times == null || discountFactors == null) {
            throw new IllegalArgumentException("times and discountFactors must not be null");
        }
        if (times.length == 0 || times.length != discountFactors.length) {
            throw new IllegalArgumentException("times and discountFactors must be non-empty and of equal length");
        }
        double previous = 0.0;
        for (int i = 0; i < times.length; i++) {
            if (!Double.isFinite(times[i]) || times[i] <= previous) {
                throw new IllegalArgumentException("times must be positive and strictly increasing (index " + i + ")");
            }
            if (!Double.isFinite(discountFactors[i]) || discountFactors[i] <= 0.0) {
                throw new IllegalArgumentException("discount factors must be positive and finite (index " + i + ")");
            }
            previous = times[i];
        }
        this.times = Arrays.copyOf(times, times.length);
        this.discountFactors = Arrays.copyOf(discountFactors, discountFactors.length);
    }

    /**
     * Gets the Discount Factor Z(t) for a given time to maturity.
     */
    public double getDiscountFactor(double t) {
        if (Double.isNaN(t)) {
            throw new IllegalArgumentException("t must not be NaN");
        }
        if (t <= 0) return 1.0;

        int index = Arrays.binarySearch(times, t);
        if (index >= 0) {
            return discountFactors[index];
        }

        int insertionPoint = -index - 1;

        // Short end: flat zero rate
        if (insertionPoint == 0) {
            double rate = -Math.log(discountFactors[0]) / times[0];
            return Math.exp(-rate * t);
        }

        // Long end
        if (insertionPoint == times.length) {
            int last = times.length - 1;
            if (last == 0) {
                double rate = -Math.log(discountFactors[0]) / times[0];
                return Math.exp(-rate * t); // one pillar: no forward to hold, so hold the zero rate
            }
            double fwdRate = -Math.log(discountFactors[last] / discountFactors[last - 1])
                           / (times[last] - times[last - 1]);
            return discountFactors[last] * Math.exp(-fwdRate * (t - times[last]));
        }

        // Log-linear interpolation for points between known pillars
        int i = insertionPoint;
        double t1 = times[i - 1];
        double t2 = times[i];
        double weight = (t - t1) / (t2 - t1);
        double logDf = (1.0 - weight) * Math.log(discountFactors[i - 1]) + weight * Math.log(discountFactors[i]);

        return Math.exp(logDf);
    }

    /**
     * Gets the continuously compounded zero-coupon rate for a given time to maturity.
     */
    public double getZeroRate(double t) {
        if (Double.isNaN(t)) {
            throw new IllegalArgumentException("t must not be NaN");
        }
        if (t <= 0) return getZeroRate(1e-5); // Limit as t->0
        return -Math.log(getDiscountFactor(t)) / t;
    }
}
