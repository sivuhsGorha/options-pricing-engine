package com.sbk.optionspricer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight YAML configuration reader with env-var override support.
 * Intended to centralize market data, risk, and dashboard settings while staying
 * compatible with the existing Java 25 project without extra dependencies.
 */
public final class ConfigManager {
    public static final String DEFAULT_CONFIG_PATH = "config.yaml";

    // Default risk limits: the single source of truth for both the generated config and the
    // application's fallbacks.
    public static final double DEFAULT_MAX_NOTIONAL = 1_000_000.0;
    public static final double DEFAULT_MAX_DELTA = 5_000.0;
    public static final double DEFAULT_MAX_GAMMA = 1_000.0;
    public static final double DEFAULT_MAX_VEGA = 10_000.0;
    public static final double DEFAULT_MAX_POSITION = 10_000.0;

    /** Sections that environment variables, .env entries and system properties may override. */
    private static final String[] OVERRIDE_SECTIONS = {
            "market_data", "volatility", "risk", "dashboard", "infrastructure", "routing", "margin", "execution", "strategy"};
    /** Keys that look like credentials are never read into config, whatever section they appear under. */
    private static final java.util.regex.Pattern SECRET_NAME =
            java.util.regex.Pattern.compile("secret|password|passwd|token|api_?key|credential");

    private final Path configPath;
    private final Map<String, Object> root;

    public ConfigManager() {
        this(Path.of(DEFAULT_CONFIG_PATH));
    }

    public ConfigManager(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.root = loadOrDefault(configPath);
    }

    public static ConfigManager fromFile(Path path) {
        return new ConfigManager(path);
    }

    public Map<String, Object> getRoot() {
        return Collections.unmodifiableMap(root);
    }

    public Object get(String key) {
        return get(root, key);
    }

    public String getString(String key, String defaultValue) {
        Object value = get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public int getInt(String key, int defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    public double getDouble(String key, double defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public Map<String, Object> getSection(String key) {
        Object value = get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Collections.unmodifiableMap(typed);
        }
        return Map.of();
    }

    public void reload() {
        Map<String, Object> reloaded = loadOrDefault(configPath);
        root.clear();
        root.putAll(reloaded);
    }

    private static Map<String, Object> loadOrDefault(Path configPath) {
        Map<String, Object> loaded;
        if (Files.exists(configPath)) {
            try {
                loaded = parseYaml(Files.readString(configPath, StandardCharsets.UTF_8));
            } catch (IOException e) {
                loaded = defaultConfig();
            }
        } else {
            loaded = defaultConfig();
            try {
                Files.writeString(configPath, renderYaml(loaded), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Ignore filesystem issues; we still have in-memory config.
            }
        }
        applyEnvironmentOverrides(loaded);
        return loaded;
    }

    private static Map<String, Object> defaultConfig() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> marketData = new LinkedHashMap<>();
        marketData.put("sources", List.of("yahoo_finance", "td_ameritrade"));
        marketData.put("refresh_interval_seconds", 900);
        marketData.put("live_data_enabled", true);

        Map<String, Object> volatility = new LinkedHashMap<>();
        volatility.put("ssvi_enabled", true);
        volatility.put("sabr_enabled", true);
        volatility.put("default_volatility", 0.2);

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("max_notional", DEFAULT_MAX_NOTIONAL);
        risk.put("max_delta", DEFAULT_MAX_DELTA);
        risk.put("max_gamma", DEFAULT_MAX_GAMMA);
        risk.put("max_vega", DEFAULT_MAX_VEGA);
        risk.put("max_position", DEFAULT_MAX_POSITION);

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("port", 8082);
        dashboard.put("update_rate_hz", 20);

        root.put("market_data", marketData);
        root.put("volatility", volatility);
        root.put("risk", risk);
        root.put("dashboard", dashboard);
        return root;
    }

    private static void applyEnvironmentOverrides(Map<String, Object> root) {
        for (Map.Entry<String, String> entry : EnvironmentConfigLoader.load(Path.of(".env")).entrySet()) {
            String normalized = entry.getKey().toLowerCase(Locale.ROOT).replace('-', '_');
            if (!normalized.contains("_")) continue;
            applyOverride(root, normalized, coerceScalar(entry.getValue()));
        }
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            String key = String.valueOf(entry.getKey());
            String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
            if (!normalized.contains("_")) continue;
            applyOverride(root, normalized, coerceScalar(String.valueOf(entry.getValue())));
        }
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.equals("_")) continue;
            String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
            if (!normalized.contains("_")) continue;
            applyOverride(root, normalized, coerceScalar(entry.getValue()));
        }
    }

    /**
     * Applies an override only to a known section, and never for credential-looking names, so
     * unrelated environment variables and secrets cannot end up in the config map.
     */
    private static void applyOverride(Map<String, Object> root, String normalizedKey, Object value) {
        if (normalizedKey == null || normalizedKey.isBlank()) return;
        for (String sectionName : OVERRIDE_SECTIONS) {
            if (normalizedKey.startsWith(sectionName + "_")) {
                String remainder = normalizedKey.substring(sectionName.length() + 1);
                if (remainder.isEmpty() || SECRET_NAME.matcher(remainder).find()) {
                    return;
                }
                Object section = root.get(sectionName);
                if (section == null) {
                    section = new LinkedHashMap<String, Object>();
                    root.put(sectionName, section);
                }
                if (section instanceof Map<?, ?> nestedMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> target = (Map<String, Object>) nestedMap;
                    target.put(remainder, value);
                }
                return;
            }
        }
        // Not in an overridable section: ignore.
    }

    private static Object get(Map<String, Object> map, String key) {
        if (key == null || key.isBlank()) return null;
        String[] parts = key.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map<?, ?> section) {
                current = section.get(part.toLowerCase(Locale.ROOT).replace('-', '_'));
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    private static Object coerceScalar(String raw) {
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.matches("-?\\d+")) {
            return Long.parseLong(trimmed);
        }
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(trimmed);
        }
        return trimmed;
    }

    private static Map<String, Object> parseYaml(String yaml) {
        Map<String, Object> root = new LinkedHashMap<>();
        Deque<Map<String, Object>> stack = new ArrayDeque<>();
        Deque<Integer> indentStack = new ArrayDeque<>();
        stack.push(root);
        indentStack.push(-1);

        for (String rawLine : yaml.lines().toList()) {
            if (rawLine.trim().isEmpty() || rawLine.trim().startsWith("#")) {
                continue;
            }

            int indent = countLeadingSpaces(rawLine);
            String line = rawLine.stripLeading();
            while (indent <= indentStack.peek()) {
                stack.pop();
                indentStack.pop();
            }

            if (!line.contains(":")) {
                continue;
            }

            String[] split = line.split(":", 2);
            String key = split[0].trim();
            String remainder = split.length > 1 ? split[1].trim() : "";
            Map<String, Object> current = stack.peek();
            if (current == null) {
                current = root;
            }

            if (remainder.isEmpty()) {
                Map<String, Object> section = new LinkedHashMap<>();
                current.put(key, section);
                stack.push(section);
                indentStack.push(indent);
            } else {
                current.put(key, parseScalar(remainder));
            }
        }
        return root;
    }

    private static int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static Object parseScalar(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) return List.of();
            List<String> items = new ArrayList<>();
            for (String part : inner.split(",")) {
                items.add(part.trim().replace("\"", ""));
            }
            return items;
        }
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.matches("-?\\d+")) {
            return Integer.parseInt(trimmed);
        }
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(trimmed);
        }
        return trimmed;
    }

    private static String renderYaml(Map<String, Object> map) {
        StringBuilder out = new StringBuilder();
        renderYaml(map, 0, out);
        return out.toString();
    }

    private static void renderYaml(Map<String, Object> map, int indent, StringBuilder out) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String indentText = "  ".repeat(indent);
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                out.append(indentText).append(entry.getKey()).append(":\n");
                renderYaml((Map<String, Object>) nested, indent + 1, out);
            } else {
                out.append(indentText).append(entry.getKey()).append(": ").append(value).append("\n");
            }
        }
    }
}
