package com.sbk.optionspricer;

import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.config.ConfigValidator;
import com.sbk.optionspricer.execution.Order;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found on review (2026-10-10): the application built its pre-trade filter with no liquidity monitor, so the spread
 * and volume gate that README, RISK.md and EXECUTION.md describe as active never ran. The filter is now built in one
 * place from the configuration, and these tests pin that the gate is on.
 */
class LiquidityWiringTest {

    @TempDir
    Path dir;

    private ConfigManager config(String risk) throws Exception {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\nrisk:\n  max_notional: 1000000\n" + risk);
        return ConfigManager.fromFile(file);
    }

    private static PreTradeRiskFilter filter(ConfigManager config) {
        return AppCompositionRoot.preTradeFilter(config, "SPY", 10_000.0, 1_000_000.0, 1_000_000.0);
    }

    @Test
    void theGateIsOnByDefaultAtTwoHundredBasisPointsAndOneContract() throws Exception {
        PreTradeRiskFilter filter = filter(config(""));
        Order buy = new Order(0, true, 1, 10.40);

        assertTrue(filter.checkRisk(buy, "SPY", 10.36, 10.45, 500L, 100), "an ATM SPY option, about 86 bps wide, with volume");
        assertFalse(filter.checkRisk(buy, "SPY", 1.00, 1.05, 500L, 100), "about 490 bps wide: too wide to trade");
        assertFalse(filter.checkRisk(buy, "SPY", 10.36, 10.45, 0L, 100), "a contract that has not traded today");
    }

    @Test
    void aFieldTheProviderDidNotSupplyIsNeitherAPassNorAFailure() throws Exception {
        PreTradeRiskFilter filter = filter(config(""));
        Order shares = new Order(0, true, 10, 776.45);

        assertTrue(filter.checkRisk(shares, "SPY", Double.NaN, Double.NaN, -1L, 1), "Finnhub publishes neither a book nor a volume");
        assertFalse(filter.checkRisk(shares, "SPY", Double.NaN, Double.NaN, 0L, 1), "a known volume of zero is still judged");
    }

    @Test
    void theLimitsComeFromTheConfiguration() throws Exception {
        PreTradeRiskFilter strict = filter(config("  max_spread_bps: 50\n  min_volume: 100\n"));
        Order buy = new Order(0, true, 1, 10.40);

        assertFalse(strict.checkRisk(buy, "SPY", 10.36, 10.45, 500L, 100), "86 bps against a 50 bps limit");
        assertFalse(strict.checkRisk(buy, "SPY", 10.40, 10.42, 50L, 100), "20 bps is fine but 50 contracts is under the 100 minimum");
        assertTrue(strict.checkRisk(buy, "SPY", 10.40, 10.42, 500L, 100));
    }

    @Test
    void theKeysAreValidated() throws Exception {
        List<String> errors = ConfigValidator.validate(config("  max_spread_bps: 0\n  min_volume: 0.5\n"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.max_spread_bps")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.min_volume")), errors.toString());

        List<String> fine = ConfigValidator.validate(config("  max_spread_bps: 200\n  min_volume: 1\n"));
        assertTrue(fine.stream().noneMatch(e -> e.contains("max_spread_bps") || e.contains("min_volume")), fine.toString());
        List<String> absent = ConfigValidator.validate(config(""));
        assertTrue(absent.stream().noneMatch(e -> e.contains("max_spread_bps") || e.contains("min_volume")), "both keys are optional: " + absent);
    }
}
