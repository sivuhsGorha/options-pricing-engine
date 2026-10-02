package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SsviSurfaceTest {

    @Test
    void testCalendarArbitrageFree() {
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(1.0, 0.25, -0.5);
        double atmVol = 0.20;
        
        for (double k = -2.0; k <= 2.0; k += 0.05) { // Dense k-grid
            double prevW = 0.0;
            for (double t = 0.1; t <= 2.0; t += 0.1) {
                double theta = atmVol * atmVol * t;
                double w = SsviApproximation.totalVariance(k, theta, params);
                assertTrue(w >= prevW - 1e-9, "Total variance must be non-decreasing in T (No Calendar Arbitrage)");
                prevW = w;
            }
        }
    }

    @Test
    void testButterflyArbitrageFree() {
        SsviApproximation.SsviParams params = new SsviApproximation.SsviParams(1.0, 0.25, -0.5);
        double atmVol = 0.20;
        
        for (double t = 0.1; t <= 2.0; t += 0.1) { // Per maturity
            double theta = atmVol * atmVol * t;
            for (double k = -3.0; k <= 3.0; k += 0.01) { // Dense k-grid
                assertTrue(SsviApproximation.isArbitrageFree(k, theta, params), 
                        "Butterfly arbitrage detected (density < 0) at k=" + k + ", t=" + t);
            }
        }
    }

    @Test
    void testButterflyArbitrageNegative() {
        // Violating parameters: eta very high, strong rho -> creates negative density (butterfly arbitrage)
        SsviApproximation.SsviParams badParams = new SsviApproximation.SsviParams(5.0, 0.49, -0.99);
        double atmVol = 0.20;
        double t = 1.0;
        double theta = atmVol * atmVol * t;
        
        boolean foundArbitrage = false;
        for (double k = -2.0; k <= 2.0; k += 0.01) {
            if (!SsviApproximation.isArbitrageFree(k, theta, badParams)) {
                foundArbitrage = true;
                break;
            }
        }
        assertTrue(foundArbitrage, "Should have detected butterfly arbitrage with extreme parameters");
    }
}
