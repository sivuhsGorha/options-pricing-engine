package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BlackScholesZeroVolTest {

    @Test
    void testStandardBlackScholesCallGoldenCase() {
        OptionParameters params = OptionParameters.noDividend(100.0, 90.0, 1.0, 0.05, 0.20);
        double callPrice = BlackScholesPricer.price(OptionType.CALL, params);
        assertEquals(16.6994, callPrice, 1e-4, "Golden case BSM Call price should be 16.6994");
    }

    @Test
    void testZeroVolatilityDiscountedPayoff() {
        OptionParameters params = OptionParameters.noDividend(100.0, 90.0, 1.0, 0.05, 0.0);
        double callPrice = BlackScholesPricer.price(OptionType.CALL, params);
        double putPrice = BlackScholesPricer.price(OptionType.PUT, params);

        assertEquals(14.3894, callPrice, 1e-4, "Zero-vol Call price should be discounted deterministic payoff 14.3894");
        assertEquals(0.0, putPrice, 1e-9, "Zero-vol ITM Call put price should be 0.0");
    }

    @Test
    void testPutCallParityGrid() {
        double spot = 100.0;
        double[] strikes = {80.0, 90.0, 100.0, 110.0, 120.0};
        double[] rates = {-0.01, 0.0, 0.05};
        double[] yields = {0.0, 0.02};
        double[] expiries = {1.0 / 365.0, 0.5, 2.0};
        double[] vols = {0.0, 0.05, 0.20, 0.50};

        for (double k : strikes) {
            for (double r : rates) {
                for (double q : yields) {
                    for (double t : expiries) {
                        for (double vol : vols) {
                            OptionParameters params = new OptionParameters(spot, k, t, r, vol, q);
                            double call = BlackScholesPricer.price(OptionType.CALL, params);
                            double put = BlackScholesPricer.price(OptionType.PUT, params);

                            double parityDiff = call - put;
                            double expectedParityDiff = spot * Math.exp(-q * t) - k * Math.exp(-r * t);

                            assertEquals(expectedParityDiff, parityDiff, 1e-9,
                                    String.format("Put-call parity failed for K=%.1f, r=%.2f, q=%.2f, T=%.4f, vol=%.2f", k, r, q, t, vol));
                        }
                    }
                }
            }
        }
    }
}
