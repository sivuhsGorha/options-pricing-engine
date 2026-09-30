package com.sbk.optionspricer.rates;

import java.util.Arrays;

/**
 * A bootstrapped Yield Curve that provides continuous discount factors and 
 * zero-coupon rates using Log-Linear interpolation on Discount Factors 
 * (which mathematically implies piecewise-constant forward rates to prevent arbitrage).
 */
public class YieldCurve {
    
    private final double[] times;
    private final double[] discountFactors;

    public YieldCurve(double[] times, double[] discountFactors) {
        this.times = times;
        this.discountFactors = discountFactors;
    }

    /**
     * Gets the Discount Factor Z(t) for a given time to maturity.
     */
    public double getDiscountFactor(double t) {
        if (t <= 0) return 1.0;
        
        int index = Arrays.binarySearch(times, t);
        if (index >= 0) {
            return discountFactors[index];
        }
        
        int insertionPoint = -index - 1;
        
        // Extrapolate flat forwards on the left (short end)
        if (insertionPoint == 0) {
            double rate = -Math.log(discountFactors[0]) / times[0];
            return Math.exp(-rate * t);
        }
        
        // Extrapolate flat forwards on the right (long end)
        if (insertionPoint == times.length) {
            int last = times.length - 1;
            double fwdRate = -Math.log(discountFactors[last] / discountFactors[last - 1]) 
                           / (times[last] - times[last - 1]);
            return discountFactors[last] * Math.exp(-fwdRate * (t - times[last]));
        }
        
        // Log-linear interpolation for points between known pillars
        int i = insertionPoint;
        double t1 = times[i - 1];
        double t2 = times[i];
        double df1 = discountFactors[i - 1];
        double df2 = discountFactors[i];
        
        double weight = (t - t1) / (t2 - t1);
        double logDf = (1.0 - weight) * Math.log(df1) + weight * Math.log(df2);
        
        return Math.exp(logDf);
    }
    
    /**
     * Gets the continuously compounded zero-coupon rate for a given time to maturity.
     */
    public double getZeroRate(double t) {
        if (t <= 0) return getZeroRate(1e-5); // Limit as t->0
        return -Math.log(getDiscountFactor(t)) / t;
    }
}
