package com.sbk.optionspricer.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code risk.max_theta} and {@code risk.max_rho} are known, optional, and must be positive when given. */
class ThetaRhoConfigTest {

    private static ConfigManager config(String riskSection) throws Exception {
        Path yaml = Files.createTempFile("theta-rho", ".yaml");
        Files.writeString(yaml, "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\nrisk:\n" + riskSection);
        return ConfigManager.fromFile(yaml);
    }

    @Test
    void theLimitsAreKnownKeysAndAbsentOnesAreNotAnError() throws Exception {
        List<String> errors = ConfigValidator.validate(config("  max_delta: 5000\n  max_theta: 1500.5\n  max_rho: 25000\n"));
        assertEquals(List.of(), errors);

        List<String> withoutThem = ConfigValidator.validate(config("  max_delta: 5000\n"));
        assertEquals(List.of(), withoutThem, "theta and rho limits are optional");
    }

    @Test
    void aZeroNegativeOrNonNumericLimitIsAnErrorThatNamesTheKey() throws Exception {
        List<String> errors = ConfigValidator.validate(config("  max_theta: 0\n  max_rho: -3\n"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.max_theta")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.max_rho")), errors.toString());

        List<String> text = ConfigValidator.validate(config("  max_theta: lots\n"));
        assertTrue(text.stream().anyMatch(e -> e.contains("risk.max_theta")), text.toString());
    }

    @Test
    void theEngineReadsThemAsNaNWhenUnset() throws Exception {
        ConfigManager config = config("  max_delta: 5000\n");
        assertTrue(Double.isNaN(config.getDouble("risk.max_theta", Double.NaN)));
        assertEquals(1500.5, config("  max_theta: 1500.5\n").getDouble("risk.max_theta", Double.NaN), 1e-9);
    }
}
