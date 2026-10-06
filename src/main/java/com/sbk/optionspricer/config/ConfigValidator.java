package com.sbk.optionspricer.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates essential config keys for market data and dashboard operation.
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

        double refresh = config.getDouble("market_data.refresh_interval_seconds", 900.0);
        if (refresh <= 0.0 || Double.isNaN(refresh) || Double.isInfinite(refresh)) {
            errors.add("market_data.refresh_interval_seconds must be positive");
        }

        int port = config.getInt("dashboard.port", 8082);
        if (port <= 0 || port > 65535) {
            errors.add("dashboard.port must be in the range 1..65535");
        }

        double vol = config.getDouble("volatility.default_volatility", 0.20);
        if (vol <= 0.0 || vol > 5.0) {
            errors.add("volatility.default_volatility must be in (0, 5]");
        }

        Map<String, Object> risk = config.getSection("risk");
        if (!risk.isEmpty()) {
            double deltaLimit = config.getDouble("risk.delta_limit", 1.0);
            double gammaLimit = config.getDouble("risk.gamma_limit", 1.0);
            double vegaLimit = config.getDouble("risk.vega_limit", 1.0);
            if (deltaLimit <= 0.0 || !Double.isFinite(deltaLimit)) {
                errors.add("risk.delta_limit must be a finite positive number");
            }
            if (gammaLimit <= 0.0 || !Double.isFinite(gammaLimit)) {
                errors.add("risk.gamma_limit must be a finite positive number");
            }
            if (vegaLimit <= 0.0 || !Double.isFinite(vegaLimit)) {
                errors.add("risk.vega_limit must be a finite positive number");
            }
        }
        return errors;
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
