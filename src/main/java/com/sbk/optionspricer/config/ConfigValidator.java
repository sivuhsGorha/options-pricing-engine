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

    /**
     * The keys each safety-relevant section reads. An unknown key in one of these sections is an error because a
     * misspelt limit or strategy setting is ignored, the default applies, and nothing says so. Other sections are
     * not checked: they hold keys of older versions that are now ignored.
     */
    private static final Map<String, java.util.Set<String>> KNOWN_KEYS = Map.of(
            "risk", java.util.Set.of("max_notional", "max_delta", "max_gamma", "max_vega", "max_position", "max_concentration",
                    "max_spread_bps", "min_volume"),
            "strategy", java.util.Set.of("mode", "trigger_pct", "base_quantity", "vol_edge", "reference_model", "hedge_band",
                    "min_days_to_expiry", "max_days_to_expiry", "contracts", "option_interval_seconds"),
            "execution", java.util.Set.of("symbol", "transport", "fill_wait_seconds", "slippage_bps", "contract_multiplier"));
    /** Renamed risk keys: they have their own, more helpful message below. */
    private static final java.util.Set<String> LEGACY_RISK_KEYS = java.util.Set.of("delta_limit", "gamma_limit", "vega_limit");

    private static void unknownKeys(ConfigManager config, List<String> errors) {
        Map<String, java.util.Set<String>> fileKeys = config.fileKeys();
        for (Map.Entry<String, java.util.Set<String>> known : KNOWN_KEYS.entrySet()) {
            for (String key : fileKeys.getOrDefault(known.getKey(), java.util.Set.of())) {
                if (known.getValue().contains(key) || (known.getKey().equals("risk") && LEGACY_RISK_KEYS.contains(key))) {
                    continue;
                }
                String nearest = nearest(key, known.getValue());
                errors.add(known.getKey() + "." + key + " is not a setting this application reads"
                        + (nearest == null ? " (known: " + new java.util.TreeSet<>(known.getValue()) + ")"
                                : " (did you mean " + known.getKey() + "." + nearest + "?)"));
            }
        }
    }

    /** The known key within two edits of {@code key}, or null. */
    private static String nearest(String key, java.util.Set<String> known) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : new java.util.TreeSet<>(known)) {
            int distance = editDistance(key, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    public static List<String> validate(ConfigManager config) {
        if (config == null) {
            return List.of("config must not be null");
        }

        List<String> errors = new ArrayList<>();
        unknownKeys(config, errors);
        Map<String, Object> marketData = config.getSection("market_data");

        if (marketData.isEmpty()) {
            errors.add("market_data section missing");
        }

        number(config, errors, "market_data.refresh_interval_seconds", 900.0,
                v -> v > 0.0, "must be a positive number of seconds");
        number(config, errors, "market_data.quote_refresh_seconds", 60.0,
                v -> v >= 10.0 && v <= 900.0, "must be between 10 and 900 seconds (how often option quotes are reloaded between calibrations)");
        number(config, errors, "market_data.spot", 100.0, v -> v > 0.0, "must be positive");
        number(config, errors, "market_data.risk_free_rate", 0.05, v -> v > -1.0 && v < 1.0, "must be a decimal rate such as 0.05");
        number(config, errors, "market_data.dividend_yield", 0.0, v -> v >= 0.0 && v < 1.0, "must be a decimal yield such as 0.015");
        number(config, errors, "volatility.default_volatility", 0.20, v -> v > 0.0 && v <= 5.0, "must be in (0, 5]");

        Map<String, Object> risk = config.getSection("risk");
        String[][] legacyKeys = {{"delta_limit", "max_delta"}, {"gamma_limit", "max_gamma"}, {"vega_limit", "max_vega"}};
        for (String[] legacy : legacyKeys) {
            if (risk.containsKey(legacy[0])) {
                errors.add("risk." + legacy[0] + " is no longer used; rename it to risk." + legacy[1]
                        + " (the value is not converted, so review it)");
            }
        }
        for (String key : new String[]{"max_notional", "max_delta", "max_gamma", "max_vega", "max_position", "max_concentration", "max_spread_bps"}) {
            if (risk.containsKey(key)) {
                number(config, errors, "risk." + key, Double.NaN, v -> Double.isFinite(v) && v > 0.0, "must be a finite positive number");
            }
        }
        if (risk.containsKey("min_volume")) {
            number(config, errors, "risk.min_volume", Double.NaN, v -> Double.isFinite(v) && v >= 1.0 && v == Math.rint(v),
                    "must be a whole number of at least 1");
        }

        number(config, errors, "strategy.trigger_pct", 0.001, v -> v > 0.0 && v < 1.0,
                "must be between 0 and 1 exclusive (0.001 means a 0.1% move)");
        number(config, errors, "strategy.base_quantity", 10.0, v -> v >= 1.0 && v == Math.rint(v),
                "must be a whole number of at least 1");
        String mode = config.getString("strategy.mode", "vol_spread");
        if (!"momentum".equals(mode) && !"vol_spread".equals(mode)) {
            errors.add("strategy.mode must be momentum or vol_spread (found " + mode + ")");
        }
        number(config, errors, "strategy.vol_edge", 0.01, v -> v > 0.0 && v < 0.5, "must be a vol fraction between 0 and 0.5 (0.01 is one point)");
        number(config, errors, "strategy.hedge_band", 50.0, v -> v >= 0.0, "must be zero or positive (net delta in shares)");
        number(config, errors, "strategy.max_days_to_expiry", 7.0, v -> v >= 0.0 && v == Math.rint(v), "must be a whole number of days");
        number(config, errors, "strategy.min_days_to_expiry", 14.0, v -> v >= 1.0 && v == Math.rint(v), "must be a whole number of days");
        number(config, errors, "strategy.contracts", 1.0, v -> v >= 1.0 && v == Math.rint(v), "must be a whole number of at least 1");
        number(config, errors, "strategy.option_interval_seconds", 30.0, v -> v >= 1.0, "must be at least 1 second");
        String reference = config.getString("strategy.reference_model", "SSVI");
        if (!"SSVI".equalsIgnoreCase(reference) && !"SVI".equalsIgnoreCase(reference)) {
            errors.add("strategy.reference_model must be SSVI or SVI (found " + reference + ")");
        }
        try {
            if (config.getDouble("strategy.min_days_to_expiry", 14.0) <= config.getDouble("strategy.max_days_to_expiry", 7.0)) {
                errors.add("strategy.min_days_to_expiry must exceed strategy.max_days_to_expiry, or a straddle would open and close at once");
            }
        } catch (ConfigException alreadyReported) {
            // the individual checks above named the bad value
        }
        number(config, errors, "execution.contract_multiplier", 1.0, v -> v >= 1.0 && v == Math.rint(v),
                "must be a whole number of at least 1 (1 for shares, 100 for standard equity options)");
        number(config, errors, "execution.slippage_bps", 25.0, v -> Double.isFinite(v) && v >= 0.0, "must be zero or positive");
        String symbol = config.getString("execution.symbol", "SPY");
        if (symbol == null || symbol.isBlank()) {
            errors.add("execution.symbol must not be blank");
        }
        String transport = config.getString("execution.transport", "paper");
        if (!"paper".equals(transport) && !"alpaca".equals(transport)) {
            errors.add("execution.transport must be paper or alpaca (found '" + transport + "')");
        } else if ("alpaca".equals(transport)) {
            String keyId = EnvironmentConfigLoader.get("ALPACA_KEY_ID");
            String secret = EnvironmentConfigLoader.get("ALPACA_SECRET");
            if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
                errors.add("execution.transport is alpaca but ALPACA_KEY_ID and ALPACA_SECRET are not both set in .env");
            }
        }
        number(config, errors, "execution.fill_wait_seconds", 10.0, v -> v >= 1.0 && v <= 120.0, "must be between 1 and 120 seconds");
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
