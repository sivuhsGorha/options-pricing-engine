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
 * Minimal YAML configuration reader with environment-variable overrides and no dependencies.
 *
 * <p>Supported syntax: {@code section:} headers, two-space indentation, {@code key: value} scalars (numbers,
 * {@code true}/{@code false}, quoted or bare strings), inline lists {@code [a, "b"]}, and {@code #} comments on
 * their own line or after a value. Tabs, block lists ({@code - item}), lines without a key and duplicate keys are
 * errors that name the line; a value of the wrong type is an error that names the key and the value. Earlier
 * versions silently skipped bad lines and fell back to defaults on an unreadable file, which hid every mistake.
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
    /** Widest bid-ask spread, in basis points of the mid, an order may be sent into. */
    public static final double DEFAULT_MAX_SPREAD_BPS = 200.0;
    /** Smallest known volume (contracts or shares traded today) an order may be sent into. */
    public static final long DEFAULT_MIN_VOLUME = 1L;

    /** Sections that environment variables, .env entries and system properties may override. */
    private static final String[] OVERRIDE_SECTIONS = {
            "market_data", "volatility", "risk", "dashboard", "infrastructure", "routing", "margin", "execution", "strategy"};
    /** Keys that look like credentials are never read into config, whatever section they appear under. */
    private static final java.util.regex.Pattern SECRET_NAME =
            java.util.regex.Pattern.compile("secret|password|passwd|token|api_?key|credential");

    private final Path configPath;
    private final Map<String, Object> root;
    /** Section to keys exactly as the file wrote them (normalised), captured before environment overrides are layered on. */
    private final Map<String, java.util.Set<String>> fileKeys = new LinkedHashMap<>();

    public ConfigManager() {
        this(Path.of(DEFAULT_CONFIG_PATH));
    }

    public ConfigManager(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.root = loadOrDefault(configPath, fileKeys);
    }

    public static ConfigManager fromFile(Path path) {
        return new ConfigManager(path);
    }

    public Path path() {
        return configPath;
    }

    /**
     * The keys each section had in the file, normalised like lookups (lower case, dashes as underscores), before
     * environment variables, {@code .env} entries and system properties were applied. Empty when the file did not
     * exist and the defaults were written. The validator checks these so a variable in the environment cannot
     * make a valid file fail, and a misspelt key in the file cannot go unnoticed.
     */
    public Map<String, java.util.Set<String>> fileKeys() {
        Map<String, java.util.Set<String>> copy = new LinkedHashMap<>();
        fileKeys.forEach((section, keys) -> copy.put(section, java.util.Set.copyOf(keys)));
        return Collections.unmodifiableMap(copy);
    }

    private static void recordKeys(Map<String, Object> loaded, Map<String, java.util.Set<String>> keysOut) {
        keysOut.clear();
        for (Map.Entry<String, Object> section : loaded.entrySet()) {
            if (section.getValue() instanceof Map<?, ?> keys) {
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                for (Object key : keys.keySet()) {
                    names.add(String.valueOf(key).toLowerCase(Locale.ROOT).replace('-', '_'));
                }
                keysOut.put(section.getKey().toLowerCase(Locale.ROOT).replace('-', '_'), names);
            }
        }
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

    /** @throws ConfigException when the value is present but not a whole number */
    public int getInt(String key, int defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) {
            if (n.doubleValue() != Math.rint(n.doubleValue())) throw notA(key, value, "whole number");
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw notA(key, value, "whole number");
        }
    }

    /** @throws ConfigException when the value is present but not a number */
    public double getDouble(String key, double defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw notA(key, value, "number");
        }
    }

    /** @throws ConfigException when the value is present but neither {@code true} nor {@code false} */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;
        throw notA(key, value, "boolean (true or false)");
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
        fileKeys.clear();
        Map<String, Object> reloaded = loadOrDefault(configPath, fileKeys);
        root.clear();
        root.putAll(reloaded);
    }

    private ConfigException notA(String key, Object value, String type) {
        return new ConfigException(label(configPath) + ": " + key + " = '" + value + "' is not a " + type);
    }

    private static String label(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static Map<String, Object> loadOrDefault(Path configPath, Map<String, java.util.Set<String>> keysOut) {
        Map<String, Object> loaded;
        if (Files.exists(configPath)) {
            try {
                loaded = parseYaml(Files.readString(configPath, StandardCharsets.UTF_8), label(configPath));
                recordKeys(loaded, keysOut);
            } catch (IOException e) {
                // Defaults in place of a file that exists but cannot be read would run with limits the operator never saw.
                throw new ConfigException("cannot read " + configPath + ": " + e.getMessage(), e);
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
        marketData.put("sources", List.of("cboe"));
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

    // ------------------------------------------------------------------ parsing

    /** Parses the YAML subset described on the class. Errors name {@code fileLabel} and the 1-based line. */
    static Map<String, Object> parseYaml(String yaml, String fileLabel) {
        Map<String, Object> root = new LinkedHashMap<>();
        Deque<Map<String, Object>> stack = new ArrayDeque<>();
        Deque<Integer> indentStack = new ArrayDeque<>();
        stack.push(root);
        indentStack.push(-1);

        int lineNumber = 0;
        for (String rawLine : yaml.lines().toList()) {
            lineNumber++;
            String content = stripComment(rawLine);
            if (content.isBlank()) {
                continue;
            }
            String where = fileLabel + " line " + lineNumber;
            int indent = 0;
            while (indent < content.length() && (content.charAt(indent) == ' ' || content.charAt(indent) == '\t')) {
                if (content.charAt(indent) == '\t') {
                    throw new ConfigException(where + ": indent with spaces, not tabs");
                }
                indent++;
            }
            String line = content.strip();
            while (indent <= indentStack.peek()) {
                stack.pop();
                indentStack.pop();
            }
            if (line.startsWith("- ") || line.equals("-")) {
                throw new ConfigException(where + ": block lists ('- item') are not supported; write the list inline as [a, b]");
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new ConfigException(where + ": expected 'key: value' or 'section:' but found '" + line + "'");
            }
            String key = line.substring(0, colon).trim();
            String remainder = line.substring(colon + 1).trim();
            if (key.isEmpty()) {
                throw new ConfigException(where + ": missing key before ':'");
            }
            Map<String, Object> current = stack.peek();
            if (current == null) {
                current = root;
            }
            if (current.containsKey(key)) {
                throw new ConfigException(where + ": duplicate key '" + key + "'");
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

    /** Removes a {@code #} comment that is at the start of the line or preceded by whitespace and not inside quotes. */
    static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static Object parseScalar(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1); // quoted: a string, whatever it looks like
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) return List.of();
            List<String> items = new ArrayList<>();
            for (String part : inner.split(",")) {
                String item = part.trim();
                if (item.length() >= 2 && (item.charAt(0) == '"' || item.charAt(0) == '\'') && item.charAt(item.length() - 1) == item.charAt(0)) {
                    item = item.substring(1, item.length() - 1);
                }
                items.add(item);
            }
            return items;
        }
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.matches("-?\\d+")) {
            return Integer.parseInt(trimmed);
        }
        if (trimmed.matches("-?\\d+\\.\\d+([eE][-+]?\\d+)?") || trimmed.matches("-?\\d+[eE][-+]?\\d+")) {
            return Double.parseDouble(trimmed);
        }
        return trimmed;
    }

    private static String renderYaml(Map<String, Object> map) {
        StringBuilder out = new StringBuilder();
        renderYaml(map, 0, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
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
