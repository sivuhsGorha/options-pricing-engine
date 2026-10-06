package com.sbk.optionspricer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VolatilitySurfaceCalibratorTest {

    @Test
    @DisplayName("Calibrate implied volatility for realistic call quote")
    void testCalibrateImpliedVolatility() {
        LocalDate expiry = LocalDate.now().plusDays(30);
        OptionQuote quote = new OptionQuote("SPY", expiry, 100.0, OptionType.CALL, 2.50, 2.60, 0.0, 100, 1000);
        double spot = 100.0;
        double rate = 0.05;
        double dividend = 0.01;

        double iv = VolatilitySurfaceCalibrator.calibrateImpliedVolatility(quote, spot, rate, dividend);
        assertTrue(iv > 0.05 && iv < 1.0, "Calibrated IV should be within reasonable bounds, got: " + iv);
    }

    @Test
    @DisplayName("Calibrate chain sorts strikes and computes positive implied volatilities")
    void testCalibrateChain() {
        LocalDate expiry = LocalDate.now().plusDays(60);
        List<OptionQuote> rawQuotes = List.of(
                new OptionQuote("SPY", expiry, 105.0, OptionType.CALL, 1.20, 1.30, 0.0, 50, 500),
                new OptionQuote("SPY", expiry, 95.0, OptionType.CALL, 6.40, 6.60, 0.0, 80, 800),
                new OptionQuote("SPY", expiry, 100.0, OptionType.CALL, 3.10, 3.30, 0.0, 100, 1000)
        );

        List<OptionQuote> calibrated = VolatilitySurfaceCalibrator.calibrateChain("SPY", expiry, 100.0, rawQuotes, 0.05, 0.01);
        assertEquals(3, calibrated.size());
        assertEquals(95.0, calibrated.get(0).strike());
        assertEquals(100.0, calibrated.get(1).strike());
        assertEquals(105.0, calibrated.get(2).strike());

        for (OptionQuote q : calibrated) {
            assertTrue(q.impliedVolatility() > 0.0, "Implied vol must be strictly positive");
            assertFalse(Double.isNaN(q.impliedVolatility()), "Implied vol must not be NaN");
        }
    }

    @Test
    @DisplayName("Calibrate handles input validation and fallbacks")
    void testValidationAndFallback() {
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.calibrateImpliedVolatility(null, 100.0, 0.05, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.calibrateChain("", LocalDate.now().plusDays(10), 100.0, List.of(), 0.05, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.calibrateChain("SPY", null, 100.0, List.of(), 0.05, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.calibrateChain("SPY", LocalDate.now().plusDays(10), -50.0, List.of(), 0.05, 0.0));

        // Fallback smile estimate when market price is zero/negative
        double fallbackIv = VolatilitySurfaceCalibrator.calibrateImpliedVolatility(100.0, 100.0, 0.5, 0.05, 0.01, OptionType.CALL, -1.0);
        assertEquals(0.20, fallbackIv, 1e-6);
    }
}
