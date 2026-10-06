package com.sbk.optionspricer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight .env reader used for local secrets and runtime config without
 * hardcoding credentials in the repository.
 */
public final class EnvironmentConfigLoader {
    public static final Path DEFAULT_ENV_PATH = Path.of(".env");

    private EnvironmentConfigLoader() {
    }

    public static String get(String key) {
        return get(key, DEFAULT_ENV_PATH);
    }

    public static String getOrDefault(String key, String defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : value;
    }

    public static String get(String key, Path envFile) {
        if (key == null || key.isBlank()) {
            return null;
        }

        String normalizedKey = normalize(key);
        Path resolved = envFile == null ? DEFAULT_ENV_PATH : envFile;
        Path absoluteDefaultPath = DEFAULT_ENV_PATH.toAbsolutePath().normalize();
        boolean preferExplicitFile = !resolved.toAbsolutePath().normalize().equals(absoluteDefaultPath);

        String fileValue = load(resolved).get(normalizedKey);
        if (preferExplicitFile) {
            if (fileValue != null && !fileValue.isBlank()) {
                return fileValue.trim();
            }
            String systemValue = System.getenv(normalizedKey);
            if (systemValue != null && !systemValue.isBlank()) {
                return systemValue.trim();
            }
            return null;
        }

        String systemValue = System.getenv(normalizedKey);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }
        if (fileValue == null || fileValue.isBlank()) {
            return null;
        }
        return fileValue.trim();
    }

    public static Map<String, String> load(Path envFile) {
        Path resolved = envFile == null ? DEFAULT_ENV_PATH : envFile;
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.exists(resolved)) {
            return values;
        }
        try {
            for (String rawLine : Files.readAllLines(resolved, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String key = normalize(line.substring(0, separator).trim());
                String value = parseValue(line.substring(separator + 1).trim());
                if (!key.isEmpty()) {
                    values.put(key, value);
                }
            }
        } catch (IOException ignored) {
            return values;
        }
        return values;
    }

    private static String parseValue(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String normalize(String key) {
        return key.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
