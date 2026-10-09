package com.sbk.optionspricer.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigHardeningTest {

    private final List<String> setProperties = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        setProperties.forEach(System::clearProperty);
    }

    private void prop(String key, String value) {
        System.setProperty(key, value);
        setProperties.add(key);
    }

    private static Path yaml(String content) throws Exception {
        Path file = Files.createTempFile("config-hardening", ".yaml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void secretsAndUnrelatedVariablesAreNotCopiedIntoTheConfigMap() throws Exception {
        prop("API_SECRET", "marker-secret-value-0123456789abcdef");
        prop("OPERATOR_PASSWORD", "marker-password-value");
        prop("POLYGON_API_KEY", "marker-polygon-key");
        prop("JAVA_UNRELATED_SETTING", "marker-unrelated");

        ConfigManager config = ConfigManager.fromFile(yaml("market_data:\n  refresh_interval_seconds: 900\n"));

        String dump = config.getRoot().toString();
        assertFalse(dump.contains("marker-secret-value"), "API_SECRET must not be copied into config: " + dump);
        assertFalse(dump.contains("marker-password-value"), dump);
        assertFalse(dump.contains("marker-polygon-key"), dump);
        assertFalse(dump.contains("marker-unrelated"), "arbitrary variables must not pollute config: " + dump);
    }

    @Test
    void overridesStillWorkForKnownSectionsIncludingExecutionAndStrategy() throws Exception {
        prop("RISK_MAX_DELTA", "123");
        prop("EXECUTION_SYMBOL", "QQQ");
        prop("STRATEGY_BASE_QUANTITY", "7");

        ConfigManager config = ConfigManager.fromFile(yaml("market_data:\n  refresh_interval_seconds: 900\n"));

        assertEquals(123.0, config.getDouble("risk.max_delta", -1.0), 1e-9);
        assertEquals("QQQ", config.getString("execution.symbol", "SPY"));
        assertEquals(7.0, config.getDouble("strategy.base_quantity", -1.0), 1e-9);
    }

    @Test
    void secretLookingKeysInsideKnownSectionsAreStillIgnored() throws Exception {
        prop("RISK_API_KEY", "marker-in-section");
        prop("EXECUTION_PASSWORD", "marker-in-section-2");

        ConfigManager config = ConfigManager.fromFile(yaml("market_data:\n  refresh_interval_seconds: 900\n"));

        assertFalse(config.getRoot().toString().contains("marker-in-section"), config.getRoot().toString());
    }

    @Test
    void defaultConfigUsesTheKeysTheApplicationActuallyReadsAndValidates() throws Exception {
        Path missing = Files.createTempDirectory("config-default").resolve("config.yaml");

        ConfigManager config = ConfigManager.fromFile(missing);

        assertEquals(ConfigManager.DEFAULT_MAX_DELTA, config.getDouble("risk.max_delta", -1.0), 1e-9);
        assertEquals(ConfigManager.DEFAULT_MAX_GAMMA, config.getDouble("risk.max_gamma", -1.0), 1e-9);
        assertEquals(ConfigManager.DEFAULT_MAX_VEGA, config.getDouble("risk.max_vega", -1.0), 1e-9);
        assertEquals(ConfigManager.DEFAULT_MAX_NOTIONAL, config.getDouble("risk.max_notional", -1.0), 1e-9);
        assertNull(config.get("risk.delta_limit"), "the old key nothing reads must not be generated");
        assertTrue(ConfigValidator.validate(config).isEmpty(), ConfigValidator.validate(config).toString());
    }

    @Test
    void legacyRiskKeysAreRejectedInsteadOfSilentlyIgnored() throws Exception {
        ConfigManager config = ConfigManager.fromFile(yaml(
                "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\n"
                        + "risk:\n  delta_limit: 1000\n  gamma_limit: 10\n  vega_limit: 50\n"));

        List<String> errors = ConfigValidator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.delta_limit") && e.contains("risk.max_delta")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.gamma_limit") && e.contains("risk.max_gamma")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.vega_limit") && e.contains("risk.max_vega")), errors.toString());
    }

    @Test
    void theRiskLimitsThatAreUsedAreValidated() throws Exception {
        ConfigManager config = ConfigManager.fromFile(yaml(
                "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\n"
                        + "risk:\n  max_delta: 0\n  max_gamma: -5\n  max_vega: 100\n  max_notional: 1000000\n"));

        List<String> errors = ConfigValidator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.max_delta")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("risk.max_gamma")), errors.toString());
        assertTrue(errors.stream().noneMatch(e -> e.contains("risk.max_vega")), errors.toString());
    }

    @Test
    void theQuoteRefreshCadenceIsBounded() throws Exception {
        ConfigManager tooFast = ConfigManager.fromFile(yaml(
                "market_data:\n  refresh_interval_seconds: 900\n  quote_refresh_seconds: 5\ndashboard:\n  port: 8082\n"));
        List<String> errors = ConfigValidator.validate(tooFast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("market_data.quote_refresh_seconds")), errors.toString());

        ConfigManager fine = ConfigManager.fromFile(yaml(
                "market_data:\n  refresh_interval_seconds: 900\n  quote_refresh_seconds: 60\ndashboard:\n  port: 8082\n"));
        assertTrue(ConfigValidator.validate(fine).stream().noneMatch(e -> e.contains("quote_refresh")), ConfigValidator.validate(fine).toString());

        ConfigManager absent = ConfigManager.fromFile(yaml("market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\n"));
        assertTrue(ConfigValidator.validate(absent).stream().noneMatch(e -> e.contains("quote_refresh")), "the key is optional; the default is 60 s");
    }

    @Test
    void theTransportIsValidatedAndAlpacaNeedsItsKeys() throws Exception {
        ConfigManager bogus = ConfigManager.fromFile(yaml(
                "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\nexecution:\n  transport: ibkr\n  fill_wait_seconds: 0\n"));
        List<String> errors = ConfigValidator.validate(bogus);
        assertTrue(errors.stream().anyMatch(e -> e.contains("execution.transport") && e.contains("ibkr")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("execution.fill_wait_seconds")), errors.toString());

        ConfigManager paper = ConfigManager.fromFile(yaml(
                "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\nexecution:\n  transport: paper\n  fill_wait_seconds: 10\n"));
        assertTrue(ConfigValidator.validate(paper).isEmpty(), ConfigValidator.validate(paper).toString());

        ConfigManager alpaca = ConfigManager.fromFile(yaml(
                "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\nexecution:\n  transport: alpaca\n"));
        boolean keysPresent = EnvironmentConfigLoader.get("ALPACA_KEY_ID") != null && EnvironmentConfigLoader.get("ALPACA_SECRET") != null;
        List<String> alpacaErrors = ConfigValidator.validate(alpaca);
        assertEquals(!keysPresent, alpacaErrors.stream().anyMatch(e -> e.contains("ALPACA_KEY_ID")),
                "the keys are required exactly when the transport is alpaca (present here: " + keysPresent + "): " + alpacaErrors);
    }
}
