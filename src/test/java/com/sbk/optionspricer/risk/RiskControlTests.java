package com.sbk.optionspricer.risk;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RiskControlTests {

    @Test
    void concentrationLimitBlocksLargeExposure() {
        ConcentrationLimitManager manager = new ConcentrationLimitManager(Map.of("SPY", 1_000_000.0));

        manager.addExposure("SPY", 750_000.0);
        assertTrue(manager.isWithinLimit("SPY", 750_000.0));

        manager.addExposure("SPY", 350_000.0);
        assertFalse(manager.isWithinLimit("SPY", 1_100_000.0));
    }

    @Test
    void liquidityMonitorFlagsWideSpreadsAndThinVolume() {
        LiquidityRiskMonitor monitor = new LiquidityRiskMonitor(25.0, 1000L);

        assertTrue(monitor.isMarketLiquid(100.0, 100.10, 2000L));
        assertFalse(monitor.isMarketLiquid(100.0, 101.50, 500L));
    }
}
