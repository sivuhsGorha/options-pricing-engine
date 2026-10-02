package com.sbk.optionspricer.volatility;

public class VolatilitySurfaceTest {

    public static void main(String[] args) {
        System.out.println("=== Volatility Surface Models Verification ===\n");
        
        double f = 100.0; // Forward
        double t = 1.0;   // 1 Year

        System.out.println("1. SABR Model (Hagan 2002)");
        // Parameters typical for equities:
        double alpha = 0.25; // ATM Vol ~25%
        double beta = 1.0;   // Lognormal backbone
        double rho = -0.6;   // Negative skew (leverage effect)
        double nu = 0.4;     // Vol of Vol
        
        System.out.printf(java.util.Locale.ROOT, "Parameters: alpha=%.2f, beta=%.2f, rho=%.2f, nu=%.2f%n", alpha, beta, rho, nu);
        
        double[] sabrStrikes = {80, 90, 100, 110, 120};
        for (double k : sabrStrikes) {
            double vol = SabrModel.impliedVolatility(f, k, t, alpha, beta, rho, nu);
            System.out.printf(java.util.Locale.ROOT, "Strike %3.0f -> SABR Implied Vol: %5.2f%%%n", k, vol * 100);
        }
        
        System.out.println("\n2. SVI Model (Gatheral 2004)");
        // Parameters for SVI:
        double a = 0.04;    // Base variance
        double b = 0.1;     // Slope
        double rhoSvi = -0.5; // Negative skew
        double m = 0.1;     // Shift
        double sigma = 0.1; // Smoothness
        
        System.out.printf(java.util.Locale.ROOT, "Parameters: a=%.2f, b=%.2f, rho=%.2f, m=%.2f, sigma=%.2f%n", a, b, rhoSvi, m, sigma);
        
        for (double k : sabrStrikes) {
            double logMoneyness = Math.log(k / f);
            double var = SviModel.impliedVariance(logMoneyness, a, b, rhoSvi, m, sigma);
            double vol = SviModel.impliedVolatility(var, t);
            System.out.printf(java.util.Locale.ROOT, "Strike %3.0f (log-money %5.2f) -> SVI Implied Vol: %5.2f%%%n", k, logMoneyness, vol * 100);
        }
    }
}
