package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.LiquidityRiskMonitor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PreTradeRiskFilterTest {

    @Test
    void testNegativeOrZeroQuantityRejected() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        assertFalse(filter.checkRisk(new Order(1, true, 0, 100.0)), "Zero quantity must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, -10, 100.0)), "Negative quantity must be rejected");
    }

    @Test
    void testNegativeOrZeroPriceRejected() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        assertFalse(filter.checkRisk(new Order(1, true, 10, 0.0)), "Zero price must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, 10, -50.0)), "Negative price must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, 10, Double.NaN)), "NaN price must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, 10, Double.POSITIVE_INFINITY)), "Infinity price must be rejected");
    }

    @Test
    void testValidOrderAccepted() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        assertTrue(filter.checkRisk(new Order(1, true, 10, 100.0)), "Valid order should be accepted");
    }

    private static PreTradeRiskFilter concentrationFilter(double limit) {
        return new PreTradeRiskFilter(1000, 10_000_000.0, 100, Map.of(1, "SPY"),
                new ConcentrationLimitManager(Map.of("SPY", limit)), null);
    }

    @Test
    void recordedFillsCountTowardConcentrationAndSellsReduceIt() {
        PreTradeRiskFilter filter = concentrationFilter(500.0);

        assertTrue(filter.checkRisk(new Order(1, true, 4, 100.0), "SPY", 100.0, 100.1, 2000L));
        filter.recordFill("SPY", 400.0);

        assertFalse(filter.checkRisk(new Order(1, true, 2, 100.0), "SPY", 100.0, 100.1, 2000L),
                "400 held + 200 more breaches the 500 limit");
        assertTrue(filter.checkRisk(new Order(1, false, 2, 100.0), "SPY", 100.0, 100.1, 2000L),
                "selling reduces exposure and must be allowed");

        filter.recordFill("SPY", -400.0);
        assertTrue(filter.checkRisk(new Order(1, true, 4, 100.0), "SPY", 100.0, 100.1, 2000L),
                "flat again: capacity restored");
    }

    @Test
    void shortExposureBeyondTheLimitIsAlsoBlocked() {
        PreTradeRiskFilter filter = concentrationFilter(500.0);
        filter.recordFill("SPY", -400.0);

        assertFalse(filter.checkRisk(new Order(1, false, 2, 100.0), "SPY", 100.0, 100.1, 2000L));
    }

    @Test
    void unconfiguredUnderlyingIsRejectedInsteadOfCrashing() {
        PreTradeRiskFilter filter = concentrationFilter(500.0);

        assertFalse(filter.checkRisk(new Order(1, true, 1, 100.0), "QQQ", 100.0, 100.1, 2000L));
    }

    @Test
    void testConcentrationAndLiquidityChecksAreEnforced() {
        ConcentrationLimitManager concentrationLimitManager = new ConcentrationLimitManager(Map.of("SPY", 1_000.0));
        LiquidityRiskMonitor liquidityRiskMonitor = new LiquidityRiskMonitor(25.0, 1000L);
        PreTradeRiskFilter filter = new PreTradeRiskFilter(
                1000,
                10_000_000.0,
                100,
                Map.of(1, "SPY"),
                concentrationLimitManager,
                liquidityRiskMonitor
        );

        assertFalse(filter.checkRisk(new Order(1, true, 100, 100.0), "SPY", 100.0, 100.10, 2000L));
        assertTrue(filter.checkRisk(new Order(1, true, 5, 100.0), "SPY", 100.0, 100.10, 2000L));
    }
}
