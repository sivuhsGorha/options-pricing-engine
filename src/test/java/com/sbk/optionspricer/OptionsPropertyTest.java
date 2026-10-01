package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OptionsPropertyTest {

    @Test
    void testNormalCdfMaxAbsoluteError() {
        double maxError = 0.0;
        double maxErrorX = 0.0;

        for (double x = -8.0; x <= 8.0; x += 0.001) {
            double approx = NormalDistribution.cdf(x);
            double reference = referenceNormalCdf(x);
            double error = Math.abs(approx - reference);
            if (error > maxError) {
                maxError = error;
                maxErrorX = x;
            }
        }

        System.out.printf("[CDF ACCURACY REPORT] Measured maximum absolute error of NormalDistribution.cdf: %.9e at x=%.3f%n",
                maxError, maxErrorX);
        assertTrue(maxError <= 1.5e-7, "Normal CDF max error (" + maxError + ") must be <= 1.5e-7");
    }

    @Test
    void testPriceBounds() {
        double[] spots = {50.0, 100.0, 200.0};
        double[] strikes = {40.0, 100.0, 150.0};
        double[] expiries = {0.1, 1.0, 2.0};
        double[] rates = {0.0, 0.05};
        double[] yields = {0.0, 0.02};
        double[] vols = {0.05, 0.20, 0.50};

        for (double s : spots) {
            for (double k : strikes) {
                for (double t : expiries) {
                    for (double r : rates) {
                        for (double q : yields) {
                            for (double vol : vols) {
                                OptionParameters p = new OptionParameters(s, k, t, r, vol, q);
                                double call = BlackScholesPricer.price(OptionType.CALL, p);
                                double put = BlackScholesPricer.price(OptionType.PUT, p);

                                double discSpot = s * Math.exp(-q * t);
                                double discStrike = k * Math.exp(-r * t);

                                // Call bounds
                                assertTrue(call >= Math.max(discSpot - discStrike, 0.0) - 1e-9, "Call price below lower bound");
                                assertTrue(call <= discSpot + 1e-9, "Call price exceeds upper bound");

                                // Put bounds
                                assertTrue(put >= Math.max(discStrike - discSpot, 0.0) - 1e-9, "Put price below lower bound");
                                assertTrue(put <= discStrike + 1e-9, "Put price exceeds upper bound");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void testMonotonicityInVolatility() {
        OptionParameters p1 = OptionParameters.noDividend(100.0, 100.0, 1.0, 0.05, 0.10);
        OptionParameters p2 = OptionParameters.noDividend(100.0, 100.0, 1.0, 0.05, 0.20);
        OptionParameters p3 = OptionParameters.noDividend(100.0, 100.0, 1.0, 0.05, 0.40);

        double call1 = BlackScholesPricer.price(OptionType.CALL, p1);
        double call2 = BlackScholesPricer.price(OptionType.CALL, p2);
        double call3 = BlackScholesPricer.price(OptionType.CALL, p3);

        assertTrue(call1 <= call2 && call2 <= call3, "Call price must be monotonic increasing in volatility");

        double put1 = BlackScholesPricer.price(OptionType.PUT, p1);
        double put2 = BlackScholesPricer.price(OptionType.PUT, p2);
        double put3 = BlackScholesPricer.price(OptionType.PUT, p3);

        assertTrue(put1 <= put2 && put2 <= put3, "Put price must be monotonic increasing in volatility");
    }

    @Test
    void testCallConvexityInStrike() {
        double spot = 100.0;
        double timeToExpiry = 1.0;
        double rate = 0.05;
        double vol = 0.20;
        double deltaK = 2.0;

        for (double k = 80.0; k <= 120.0; k += 5.0) {
            double cLow = BlackScholesPricer.price(OptionType.CALL, OptionParameters.noDividend(spot, k - deltaK, timeToExpiry, rate, vol));
            double cMid = BlackScholesPricer.price(OptionType.CALL, OptionParameters.noDividend(spot, k, timeToExpiry, rate, vol));
            double cHigh = BlackScholesPricer.price(OptionType.CALL, OptionParameters.noDividend(spot, k + deltaK, timeToExpiry, rate, vol));

            double secondDiff = cLow - 2.0 * cMid + cHigh;
            assertTrue(secondDiff >= -1e-9, "Call price must be convex in strike (second diff >= 0)");
        }
    }

    @Test
    void testGreeksVsCentralFiniteDifferences() {
        double spot = 100.0;
        double strike = 105.0;
        double timeToExpiry = 0.75;
        double rate = 0.04;
        double vol = 0.25;
        double yield = 0.01;

        OptionParameters p = new OptionParameters(spot, strike, timeToExpiry, rate, vol, yield);
        Greeks g = BlackScholesPricer.greeks(OptionType.CALL, p);

        // Finite difference step sizes
        double hS = 1e-4 * spot;
        double hVol = 1e-4;

        // Delta FD: (C(S+h) - C(S-h)) / (2*h)
        double cPlusS = BlackScholesPricer.price(OptionType.CALL, new OptionParameters(spot + hS, strike, timeToExpiry, rate, vol, yield));
        double cMinusS = BlackScholesPricer.price(OptionType.CALL, new OptionParameters(spot - hS, strike, timeToExpiry, rate, vol, yield));
        double fdDelta = (cPlusS - cMinusS) / (2.0 * hS);
        assertEquals(g.delta(), fdDelta, 1e-4, "Delta should match central finite difference within 1e-4");

        // Gamma FD: (C(S+h) - 2C(S) + C(S-h)) / h^2
        double cBase = BlackScholesPricer.price(OptionType.CALL, p);
        double fdGamma = (cPlusS - 2.0 * cBase + cMinusS) / (hS * hS);
        assertEquals(g.gamma(), fdGamma, 1e-3, "Gamma should match central finite difference within 1e-3");

        // Vega FD: (C(vol+h) - C(vol-h)) / (2*h)
        double cPlusV = BlackScholesPricer.price(OptionType.CALL, new OptionParameters(spot, strike, timeToExpiry, rate, vol + hVol, yield));
        double cMinusV = BlackScholesPricer.price(OptionType.CALL, new OptionParameters(spot, strike, timeToExpiry, rate, vol - hVol, yield));
        double fdVega = (cPlusV - cMinusV) / (2.0 * hVol);
        assertEquals(g.vega(), fdVega, 1e-3, "Vega should match central finite difference within 1e-3");
    }

    /** Reference high-precision Abramowitz & Stegun 26.2.17 CDF approximation. */
    private static double referenceNormalCdf(double x) {
        if (x < -8.0) return 0.0;
        if (x > 8.0) return 1.0;
        double absX = Math.abs(x);
        double t = 1.0 / (1.0 + 0.2316419 * absX);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double cdfTail = (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-0.5 * absX * absX) * poly;
        return x >= 0.0 ? 1.0 - cdfTail : cdfTail;
    }
}
