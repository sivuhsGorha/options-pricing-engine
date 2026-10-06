package com.sbk.optionspricer.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentConfigLoaderTest {

    @Test
    void loadsDotEnvValuesAndPrefersEnvOverrides() throws IOException {
        Path envFile = Files.createTempFile("aura-opt-env", ".env");
        Files.writeString(envFile,
                "FINNHUB_KEY=demo-finnhub-key\n" +
                "MARKETSTACK_KEY=\"demo-marketstack-key\"\n" +
                "# comment\n" +
                "EMPTY_KEY=\n");

        assertEquals("demo-finnhub-key", EnvironmentConfigLoader.get("FINNHUB_KEY", envFile));
        assertEquals("demo-marketstack-key", EnvironmentConfigLoader.get("MARKETSTACK_KEY", envFile));
        assertNull(EnvironmentConfigLoader.get("MISSING_KEY", envFile));
    }
}
