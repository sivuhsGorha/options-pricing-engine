package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The solver must recover the volatility itself (not just a price match), and never return a wrong root. */
class ImpliedVolatilityAccuracyTest {

    private static OptionalDouble solve(OptionType type, double s, double k, double t, double r, double q, double price) {
        return ImpliedVolatilitySolver.solve(type, s, k, t, r, q, price, new double[5]);
    }

    private static double vega(double s, double k, double t, double r, double q, double vol) {
        double d1 = (Math.log(s / k) + (r - q + 0.5 * vol * vol) * t) / (vol * Math.sqrt(t));
        return s * Math.exp(-q * t) * NormalDistribution.pdf(d1) * Math.sqrt(t);
    }

    @Test
    void recoversTheVolatilityAcrossASpreadOfContractsToTightTolerance() {
        double[] strikes = {60, 80, 95, 100, 105, 120, 150};
        double[] expiries = {1.0 / 365, 0.02, 0.1, 0.5, 2.0};
        double[] vols = {0.05, 0.12, 0.2, 0.45, 1.2, 3.0};
        int checked = 0;
        for (OptionType type : OptionType.values()) {
            for (double k : strikes) {
                for (double t : expiries) {
                    for (double vol : vols) {
                        double r = 0.03;
                        double q = 0.01;
                        double price = BlackScholesPricer.price(type, 100.0, k, t, r, vol, q);
                        OptionalDouble iv = solve(type, 100.0, k, t, r, q, price);
                        String label = type + " k=" + k + " t=" + t + " vol=" + vol + " price=" + price;
                        if (iv.isEmpty()) {
                            // Allowed only when the price carries no usable information about volatility.
                            assertTrue(price < 1e-9 || vega(100.0, k, t, r, q, vol) < 1e-7, "unexpectedly unsolved: " + label);
                            continue;
                        }
                        // Always: the returned vol must reprice the option essentially exactly.
                        double repriced = BlackScholesPricer.price(type, 100.0, k, t, r, iv.getAsDouble(), q);
                        assertEquals(price, repriced, 1e-10 * Math.max(1.0, price), "repricing " + label);
                        // And where the price is sensitive to vol, the vol itself must match.
                        if (vega(100.0, k, t, r, q, vol) > 1e-4) {
                            assertEquals(vol, iv.getAsDouble(), 1e-8, "vol " + label);
                        }
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 300, "most of the grid should be solvable, solved " + checked);
    }

    @Test
    void farOutOfTheMoneyOptionsAreNotAcceptedOnAnAbsolutePriceTolerance() {
        // Price ~1e-4 and vega ~0.02: the old test |price error| < 1e-6 allowed ~5e-5 of vol error.
        double price = BlackScholesPricer.price(OptionType.CALL, 100.0, 150.0, 0.25, 0.03, 0.2, 0.0);
        OptionalDouble iv = solve(OptionType.CALL, 100.0, 150.0, 0.25, 0.03, 0.0, price);

        assertTrue(iv.isPresent());
        assertEquals(0.2, iv.getAsDouble(), 1e-9);
    }

    @Test
    void randomRoundTripsNeverReturnAWrongVolatility() {
        Random random = new Random(7);
        for (int i = 0; i < 5_000; i++) {
            double s = 50 + random.nextDouble() * 150;
            double k = s * (0.6 + random.nextDouble() * 0.9);
            double t = 0.005 + random.nextDouble() * 3;
            double r = random.nextDouble() * 0.06;
            double q = random.nextDouble() * 0.03;
            double vol = 0.03 + random.nextDouble() * 1.5;
            OptionType type = random.nextBoolean() ? OptionType.CALL : OptionType.PUT;
            double price = BlackScholesPricer.price(type, s, k, t, r, vol, q);
            OptionalDouble iv = solve(type, s, k, t, r, q, price);
            if (iv.isPresent()) {
                double repriced = BlackScholesPricer.price(type, s, k, t, r, iv.getAsDouble(), q);
                assertEquals(price, repriced, 1e-9 * Math.max(1.0, price), "i=" + i);
            }
        }
    }

    @Test
    void rejectsPricesOutsideWhatAnyVolatilityBetweenTheBoundsCanProduce() {
        // Above the price at 500% vol.
        double tooHigh = BlackScholesPricer.price(OptionType.CALL, 100.0, 100.0, 1.0, 0.05, 6.0, 0.0);
        assertTrue(solve(OptionType.CALL, 100.0, 100.0, 1.0, 0.05, 0.0, tooHigh).isEmpty());
        // Non-positive inputs.
        assertTrue(solve(OptionType.CALL, 100.0, 100.0, 0.0, 0.05, 0.0, 5.0).isEmpty());
        assertTrue(solve(OptionType.CALL, 100.0, 100.0, 1.0, 0.05, 0.0, 0.0).isEmpty());
        assertTrue(solve(OptionType.PUT, -1.0, 100.0, 1.0, 0.05, 0.0, 5.0).isEmpty());
    }

    @Test
    void convergesInFewIterationsSoSurfaceCalibrationStaysCheap() {
        long start = System.nanoTime();
        int solved = 0;
        for (int i = 0; i < 20_000; i++) {
            double k = 80 + (i % 40);
            double price = BlackScholesPricer.price(OptionType.CALL, 100.0, k, 0.5, 0.03, 0.25, 0.0);
            if (solve(OptionType.CALL, 100.0, k, 0.5, 0.03, 0.0, price).isPresent()) solved++;
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(20_000, solved);
        assertTrue(millis < 5_000, "20,000 solves took " + millis + "ms");
    }
}
