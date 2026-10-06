package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sbk.optionspricer.models.pde.DiscreteDividendPricer;

public class AmericanPutTest {

    public static double binomialAmericanPut(double S, double K, double T, double r, double sigma, int steps) {
        double dt = T / steps;
        double u = Math.exp(sigma * Math.sqrt(dt));
        double d = 1.0 / u;
        double p = (Math.exp(r * dt) - d) / (u - d);
        double discount = Math.exp(-r * dt);
        
        double[] prices = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double spot = S * Math.pow(u, steps - i) * Math.pow(d, i);
            prices[i] = Math.max(0.0, K - spot);
        }
        
        for (int step = steps - 1; step >= 0; step--) {
            for (int i = 0; i <= step; i++) {
                double spot = S * Math.pow(u, step - i) * Math.pow(d, i);
                double expected = discount * (p * prices[i] + (1 - p) * prices[i + 1]);
                prices[i] = Math.max(expected, K - spot);
            }
        }
        return prices[0];
    }

    @Test
    void testAmericanPutConvergence() {
        double S = 100, K = 100, T = 1, r = 0.05, sigma = 0.2;
        
        double bin500 = binomialAmericanPut(S, K, T, r, sigma, 500);
        double bin1000 = binomialAmericanPut(S, K, T, r, sigma, 1000);
        double bin2000 = binomialAmericanPut(S, K, T, r, sigma, 2000);
        // A CRR tree converges as 1/N, so even 2000 steps is off by ~4e-4. Richardson extrapolation removes the
        // leading error term and is the reference the PDE should be judged against.
        double reference = 2.0 * bin2000 - bin1000;
        
        System.out.println("Binomial 500 steps: " + bin500);
        System.out.println("Binomial 1000 steps: " + bin1000);
        System.out.println("Binomial 2000 steps: " + bin2000);
        
        assertTrue(Math.abs(bin2000 - bin1000) < Math.abs(bin1000 - bin500), "Convergence should tighten");
        
        OptionParameters params = new OptionParameters(S, K, T, r, sigma, 0.0);
        double pde500 = DiscreteDividendPricer.price(OptionType.PUT, params, null, 500, 500, true);
        double pde1000 = DiscreteDividendPricer.price(OptionType.PUT, params, null, 1000, 1000, true);
        double pde2000 = DiscreteDividendPricer.price(OptionType.PUT, params, null, 2000, 2000, true);
        
        System.out.println("PDE 500 steps: " + pde500);
        System.out.println("PDE 1000 steps: " + pde1000);
        System.out.println("PDE 2000 steps: " + pde2000);
        
        double error500 = Math.abs(pde500 - reference);
        double error1000 = Math.abs(pde1000 - reference);
        double error2000 = Math.abs(pde2000 - reference);
        
        System.out.println("PDE Error at 500 steps vs bin2000: " + error500);
        System.out.println("PDE Error at 1000 steps vs bin2000: " + error1000);
        System.out.println("PDE Error at 2000 steps vs bin2000: " + error2000);
        
        assertTrue(error2000 < error1000, "PDE error should decrease as steps increase");
        
        double tolerance = 2e-4;
        System.out.println("Tolerance: " + tolerance);
        assertEquals(reference, pde2000, tolerance, "PDE should match the extrapolated binomial reference");
        
        OptionParameters eurParams = new OptionParameters(S, K, T, r, sigma, 0.0);
        double europeanPrice = BlackScholesPricer.price(OptionType.PUT, eurParams);
        assertTrue(pde2000 >= europeanPrice, "American put >= European put");
    }
}
