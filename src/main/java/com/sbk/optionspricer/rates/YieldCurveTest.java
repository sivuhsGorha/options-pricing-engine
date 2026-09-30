package com.sbk.optionspricer.rates;

public class YieldCurveTest {

    public static void main(String[] args) {
        System.out.println("=== OIS Yield Curve Bootstrapping ===\n");
        
        // Example market quotes for OIS Swaps (e.g., €STR)
        double[] maturities = {1.0, 2.0, 3.0, 5.0, 10.0};
        double[] swapRates  = {0.035, 0.032, 0.030, 0.028, 0.030}; // 3.5%, 3.2%, 3%, 2.8%, 3%
        
        System.out.println("Market Quotes:");
        for (int i = 0; i < maturities.length; i++) {
            System.out.printf("  %2.0fY Swap: %.2f%%%n", maturities[i], swapRates[i] * 100);
        }
        
        // Bootstrap the curve
        YieldCurve curve = OisCurveBootstrapper.bootstrap(maturities, swapRates);
        
        System.out.println("\nBootstrapped Discount Factors & Continuous Zero Rates:");
        
        // Let's sample the continuous curve every 0.5 years to prove interpolation
        double[] samplePoints = {0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 7.5, 10.0, 12.0};
        
        for (double t : samplePoints) {
            double df = curve.getDiscountFactor(t);
            double zRate = curve.getZeroRate(t);
            
            System.out.printf("  t = %4.1fY  |  DF: %.6f  |  Zero Rate: %5.2f%%%n", t, df, zRate * 100);
        }
    }
}
