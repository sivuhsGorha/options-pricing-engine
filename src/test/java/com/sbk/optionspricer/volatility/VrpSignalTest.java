package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrpSignalTest {

    @Test
    void testFromVolatilities() {
        double impliedVol = 0.25;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.02;

        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd);

        assertEquals(0.25, signal.impliedVol());
        assertEquals(0.20, signal.realizedForecast());
        assertEquals(0.05, signal.vrp(), 0.001);
        assertEquals(20.0, signal.vrpPercent(), 0.01);
        assertEquals(1.0, signal.vrpZScore(), 0.01);
    }

    @Test
    void testHighSignal() {
        double impliedVol = 0.30;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.02;

        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd);

        assertEquals(VrpSignal.SignalDirection.HIGH, signal.direction());
        assertTrue(signal.shouldSell());
        assertFalse(signal.shouldBuy());
        assertFalse(signal.isNeutral());
    }

    @Test
    void testLowSignal() {
        double impliedVol = 0.18;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.02;

        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd);

        assertEquals(VrpSignal.SignalDirection.LOW, signal.direction());
        assertFalse(signal.shouldSell());
        assertTrue(signal.shouldBuy());
        assertFalse(signal.isNeutral());
    }

    @Test
    void testNeutralSignal() {
        double impliedVol = 0.23;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.02;

        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd);

        assertEquals(VrpSignal.SignalDirection.NEUTRAL, signal.direction());
        assertFalse(signal.shouldSell());
        assertFalse(signal.shouldBuy());
        assertTrue(signal.isNeutral());
    }

    @Test
    void testCustomThresholds() {
        double impliedVol = 0.25;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.02;

        // With higher threshold, should be neutral
        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd, 2.0, -2.0);

        assertEquals(VrpSignal.SignalDirection.NEUTRAL, signal.direction());
    }

    @Test
    void testInvalidImpliedVol() {
        assertThrows(IllegalArgumentException.class, () -> {
            VrpSignal.fromVolatilities(-0.1, 0.2, 0.03, 0.02);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            VrpSignal.fromVolatilities(Double.NaN, 0.2, 0.03, 0.02);
        });
    }

    @Test
    void testInvalidRealizedForecast() {
        assertThrows(IllegalArgumentException.class, () -> {
            VrpSignal.fromVolatilities(0.25, -0.1, 0.03, 0.02);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            VrpSignal.fromVolatilities(0.25, Double.NaN, 0.03, 0.02);
        });
    }

    @Test
    void testZeroStandardDeviation() {
        double impliedVol = 0.25;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.0;

        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd);

        assertEquals(0.0, signal.vrpZScore());
    }

    @Test
    void testSummary() {
        double impliedVol = 0.25;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.02;

        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd);

        String summary = signal.summary();
        assertTrue(summary.contains("VRP"));
        assertTrue(summary.contains("z="));
        assertTrue(summary.contains("direction="));
    }

    @Test
    void testNegativeVrp() {
        double impliedVol = 0.15;
        double realizedForecast = 0.20;
        double vrpMean = 0.03;
        double vrpStd = 0.02;

        VrpSignal signal = VrpSignal.fromVolatilities(impliedVol, realizedForecast, vrpMean, vrpStd);

        assertTrue(signal.vrp() < 0);
        assertTrue(signal.vrpPercent() < 0);
    }
}
