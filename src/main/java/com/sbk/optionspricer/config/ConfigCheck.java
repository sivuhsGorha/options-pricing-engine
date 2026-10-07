package com.sbk.optionspricer.config;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@code --check-config}: reads the configuration and the environment, reports every problem at once, and
 * exits without starting anything. Exit code 0 when valid, 1 when not. Never prints a secret's value.
 */
public final class ConfigCheck {

    static final String[] MARKET_DATA_KEYS = {"FINNHUB_KEY", "POLYGON_API_KEY", "ALPHA_VANTAGE_KEY", "MARKETSTACK_KEY", "FRED_API_KEY"};

    private ConfigCheck() {
    }

    public static int run(Path configPath, Function<String, String> environment, PrintStream out) {
        out.println("Checking " + configPath.toAbsolutePath());
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (!Files.exists(configPath)) {
            notes.add("config file not found; the application will create it with defaults on first start (copy config.example.yaml to set your own)");
        } else {
            try {
                ConfigManager config = ConfigManager.fromFile(configPath);
                notes.add("sections: " + String.join(", ", config.getRoot().keySet()));
                errors.addAll(ConfigValidator.validate(config));
            } catch (ConfigException e) {
                errors.add(e.getMessage());
            }
        }

        errors.addAll(ConfigValidator.validateCredentials(environment.apply("API_SECRET"), environment.apply("OPERATOR_PASSWORD")));

        List<String> present = new ArrayList<>();
        for (String key : MARKET_DATA_KEYS) {
            String value = environment.apply(key);
            if (value != null && !value.isBlank()) {
                present.add(key);
            }
        }
        notes.add(present.isEmpty()
                ? "market data keys: none set, so quotes will be SIMULATED (set at least one of FINNHUB_KEY, POLYGON_API_KEY, ALPHA_VANTAGE_KEY, MARKETSTACK_KEY)"
                : "market data keys: " + String.join(", ", present));

        for (String note : notes) {
            out.println("  " + note);
        }
        if (errors.isEmpty()) {
            out.println("OK: configuration is valid");
            return 0;
        }
        out.println(errors.size() + " problem(s):");
        for (String error : errors) {
            out.println("  - " + error);
        }
        return 1;
    }
}
