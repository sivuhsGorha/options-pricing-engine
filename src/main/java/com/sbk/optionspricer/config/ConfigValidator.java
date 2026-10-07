package com.sbk.optionspricer.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates every configuration key the application reads. Each problem is one message naming the key and
 * the rule, and a value of the wrong type is reported the same way rather than thrown, so an operator sees
 * all problems at once.
 */
public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static List<String> validate(ConfigManager config) {
        if (config == null) {
            return List.of("config must not be null");
        }

        List<String> errors = new ArrayList<>();
        Map<String, Object> marketData = config.getSection("market_data");
        Map<String, Object> dashboard = config.getSection("dashboard");

        if (marketData.isEmpty()) {
            errors.add("market_data section missing");
        }
        if (dashboard.isEmpty()) {
            errors.add("dashboard section missing");
        }

        number(config, errors, "market_data.refresh_interval_seconds", 900.0,
                v -> v > 0.0, "must be a positive number of seconds");
        number(config, errors, "market_data.spot", 100.0, v -> v > 0.0, "must be positive");
        number(config, errors, "market_data.risk_free_rate", 0.05, v -> v > -1.0 && v < 1.0, "must be a decimal rate such as 0.05");
        number(config, errors, "market_data.dividend_yield", 0.0, v -> v >= 0.0 && v < 1.0, "must be a decimal yield such as 0.015");
        try {
            int port = config.getInt("dashboard.port", 8082);
            if (port <= 0 || port > 65535) {
                errors.add("dashboard.port must be in the range 1..65535");
            }
        } catch (ConfigException e) {
            errors.add(e.getMessage());
        }
        number(config, errors, "volatility.default_volatility", 0.20, v -> v > 0.0 && v <= 5.0, "must be in (0, 5]");

        Map<String, Object> risk = config.getSection("risk");
        String[][] legacyKeys = {{"delta_limit", "max_delta"}, {"gamma_limit", "max_gamma"}, {"vega_limit", "max_vega"}};
        for (String[] legacy : legacyKeys) {
            if (risk.containsKey(legacy[0])) {
                errors.add("risk." + legacy[0] + " is no longer used; rename it to risk." + legacy[1]
                        + " (the value is not converted, so review it)");
            }
        }
        for (String key : new String[]{"max_notional", "max_delta", "max_gamma", "max_vega", "max_position", "max_concentration"}) {
            if (risk.containsKey(key)) {
                number(config, errors, "risk." + key, Double.NaN, v -> Double.isFinite(v) && v > 0.0, "must be a finite positive number");
            }
        }

        number(config, errors, "strategy.trigger_pct", 0.001, v -> v > 0.0 && v < 1.0,
                "must be between 0 and 1 exclusive (0.001 means a 0.1% move)");
        number(config, errors, "strategy.base_quantity", 10.0, v -> v >= 1.0 && v == Math.rint(v),
                "must be a whole number of at least 1");
        number(config, errors, "execution.contract_multiplier", 1.0, v -> v >= 1.0 && v == Math.rint(v),
                "must be a whole number of at least 1 (1 for shares, 100 for standard equity options)");
        number(config, errors, "execution.slippage_bps", 25.0, v -> Double.isFinite(v) && v >= 0.0, "must be zero or positive");
        String symbol = config.getString("execution.symbol", "SPY");
        if (symbol == null || symbol.isBlank()) {
            errors.add("execution.symbol must not be blank");
        }
        return errors;
    }

    private static void number(ConfigManager config, List<String> errors, String key, double defaultValue,
                               java.util.function.DoublePredicate ok, String rule) {
        try {
            double value = config.getDouble(key, defaultValue);
            if (!ok.test(value)) {
                errors.add(key + " " + rule + " (found " + value + ")");
            }
        } catch (ConfigException e) {
            errors.add(e.getMessage());
        }
    }

    public static List<String> validateCredentials(String apiSecret, String operatorPassword) {
        List<String> errors = new ArrayList<>();
        if (apiSecret == null || apiSecret.isBlank()) {
            errors.add("API_SECRET must not be blank");
        } else if (apiSecret.length() < 32) {
            errors.add("API_SECRET must be at least 32 characters long");
        } else if ("default-dev-secret".equalsIgnoreCase(apiSecret.trim())) {
            errors.add("API_SECRET must not use insecure default value");
        }

        if (operatorPassword == null || operatorPassword.isBlank()) {
            errors.add("OPERATOR_PASSWORD must not be blank");
        } else if (operatorPassword.length() < 12) {
            errors.add("OPERATOR_PASSWORD must be at least 12 characters long");
        }
        return errors;
    }
}
