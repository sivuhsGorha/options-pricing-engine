package com.sbk.optionspricer;

import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigManagerTest {
    @Test
    void loadsDefaultConfigAndValidates() throws Exception {
        Path tempConfig = Files.createTempFile("aura-opt-config", ".yaml");
        Files.writeString(tempConfig, "market_data:\n  refresh_interval_seconds: 600\nvolatility:\n  default_volatility: 0.25\ndashboard:\n  port: 8083\n");

        ConfigManager config = ConfigManager.fromFile(tempConfig);
        assertEquals(600, config.getInt("market_data.refresh_interval_seconds", 900));
        assertEquals(0.25, config.getDouble("volatility.default_volatility", 0.2), 1e-9);
        assertEquals(8083, config.getInt("dashboard.port", 8082));
        assertTrue(ConfigValidator.validate(config).isEmpty(), "validation should pass for valid config");
    }

    @Test
    void envOverridesSupported() throws Exception {
        Path tempConfig = Files.createTempFile("aura-opt-config-env", ".yaml");
        Files.writeString(tempConfig, "market_data:\n  refresh_interval_seconds: 900\n");

        String before = System.getProperty("MARKET_DATA_REFRESH_INTERVAL_SECONDS");
        try {
            System.setProperty("MARKET_DATA_REFRESH_INTERVAL_SECONDS", "1200");
            ConfigManager config = ConfigManager.fromFile(tempConfig);
            assertEquals(1200, config.getInt("market_data.refresh_interval_seconds", 900));
        } finally {
            if (before == null) {
                System.clearProperty("MARKET_DATA_REFRESH_INTERVAL_SECONDS");
            } else {
                System.setProperty("MARKET_DATA_REFRESH_INTERVAL_SECONDS", before);
            }
        }
    }
}
