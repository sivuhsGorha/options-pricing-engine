package com.sbk.optionspricer.rates;

/**
 * Bootstraps a zero-coupon yield curve from market quoted Overnight Indexed Swaps (OIS)
 * such as the Euro Short-Term Rate (€STR) or SOFR swaps.
 */
public class OisCurveBootstrapper {
    
    /**
     * Bootstraps the discount curve assuming annual swap payment frequencies.
     * Uses the standard OIS pricing equation: R * SUM(dt_i * Z_i) = 1 - Z_n
     * 
     * @param maturities The maturity of each swap in years (e.g., 1.0, 2.0, 3.0)
     * @param swapRates  The quoted fixed rate for each OIS swap (e.g., 0.03 for 3%)
     * @return A calibrated YieldCurve
     */
    public static YieldCurve bootstrap(double[] maturities, double[] swapRates) {
        if (maturities.length != swapRates.length || maturities.length == 0) {
            throw new IllegalArgumentException("Invalid input arrays");
        }

        int n = maturities.length;
        double[] discountFactors = new double[n];
        
        double sumProduct = 0.0;
        double previousT = 0.0;
        
        for (int i = 0; i < n; i++) {
            double t = maturities[i];
            double dt = t - previousT;
            double rate = swapRates[i];
            
            // Solve for the discount factor at the current maturity pillar
            // Z_n = (1 - R * previous_sum) / (1 + R * dt)
            double z = (1.0 - rate * sumProduct) / (1.0 + rate * dt);
            discountFactors[i] = z;
            
            // Accumulate the annuity factor for the next iteration
            sumProduct += dt * z;
            previousT = t;
        }
        
        return new YieldCurve(maturities, discountFactors);
    }
}
