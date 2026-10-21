package com.sbk.optionspricer.logging;

import java.io.PrintStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Structured JSON logger for better log parsing and analysis.
 * Outputs logs in JSON format with timestamp, level, component, message, and optional structured data.
 *
 * Features:
 * - JSON format for machine-readable logs
 * - Log levels: DEBUG, INFO, WARN, ERROR
 * - Component tagging for filtering
 * - Structured key-value data attachment
 * - Thread-safe
 * - Configurable output stream (stdout/stderr)
 * - Backward compatibility with tag-based logging (via component field)
 */
public final class StructuredLogger {

    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    private final String component;
    private final PrintStream output;
    private final AtomicReference<Level> minLevel;

    // Thread-local context for adding fields to all logs in a scope
    private static final ThreadLocal<Map<String, Object>> context = ThreadLocal.withInitial(LinkedHashMap::new);

    // Global min level (can be changed at runtime)
    private static final AtomicReference<Level> globalMinLevel = new AtomicReference<>(Level.INFO);

    /**
     * Creates a structured logger for a component.
     *
     * @param component the component name (e.g., "ENGINE", "SURFACE", "STRATEGY")
     */
    public StructuredLogger(String component) {
        this(component, System.out);
    }

    /**
     * Creates a structured logger for a component with a specific output stream.
     *
     * @param component the component name
     * @param output the output stream (typically System.out or System.err)
     */
    public StructuredLogger(String component, PrintStream output) {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("component must not be blank");
        }
        this.component = component.toUpperCase();
        this.output = output;
        this.minLevel = globalMinLevel;
    }

    /**
     * Sets the global minimum log level. Logs below this level are suppressed.
     *
     * @param level the minimum level
     */
    public static void setGlobalMinLevel(Level level) {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        globalMinLevel.set(level);
    }

    /**
     * Gets the current global minimum log level.
     *
     * @return the minimum level
     */
    public static Level getGlobalMinLevel() {
        return globalMinLevel.get();
    }

    /**
     * Adds a context field that will be included in all logs in the current thread.
     * Useful for request IDs, user IDs, etc.
     *
     * @param key the field key
     * @param value the field value
     */
    public static void addContext(String key, Object value) {
        context.get().put(key, value);
    }

    /**
     * Removes a context field.
     *
     * @param key the field key
     */
    public static void removeContext(String key) {
        context.get().remove(key);
    }

    /**
     * Clears all context fields for the current thread.
     */
    public static void clearContext() {
        context.get().clear();
    }

    /**
     * Logs a message at DEBUG level.
     *
     * @param message the message
     */
    public void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    /**
     * Logs a message at DEBUG level with structured data.
     *
     * @param message the message
     * @param data the structured data
     */
    public void debug(String message, Map<String, Object> data) {
        log(Level.DEBUG, message, data);
    }

    /**
     * Logs a message at INFO level.
     *
     * @param message the message
     */
    public void info(String message) {
        log(Level.INFO, message, null);
    }

    /**
     * Logs a message at INFO level with structured data.
     *
     * @param message the message
     * @param data the structured data
     */
    public void info(String message, Map<String, Object> data) {
        log(Level.INFO, message, data);
    }

    /**
     * Logs a message at WARN level.
     *
     * @param message the message
     */
    public void warn(String message) {
        log(Level.WARN, message, null);
    }

    /**
     * Logs a message at WARN level with structured data.
     *
     * @param message the message
     * @param data the structured data
     */
    public void warn(String message, Map<String, Object> data) {
        log(Level.WARN, message, data);
    }

    /**
     * Logs a message at ERROR level.
     *
     * @param message the message
     */
    public void error(String message) {
        log(Level.ERROR, message, null);
    }

    /**
     * Logs a message at ERROR level with structured data.
     *
     * @param message the message
     * @param data the structured data
     */
    public void error(String message, Map<String, Object> data) {
        log(Level.ERROR, message, data);
    }

    /**
     * Logs a message at ERROR level with an exception.
     *
     * @param message the message
     * @param throwable the exception
     */
    public void error(String message, Throwable throwable) {
        log(Level.ERROR, message, null, throwable);
    }

    /**
     * Logs a message at ERROR level with structured data and an exception.
     *
     * @param message the message
     * @param data the structured data
     * @param throwable the exception
     */
    public void error(String message, Map<String, Object> data, Throwable throwable) {
        log(Level.ERROR, message, data, throwable);
    }

    /**
     * Internal log method.
     */
    private void log(Level level, String message, Map<String, Object> data) {
        log(level, message, data, null);
    }

    /**
     * Internal log method with exception.
     */
    private void log(Level level, String message, Map<String, Object> data, Throwable throwable) {
        if (level.ordinal() < minLevel.get().ordinal()) {
            return; // below minimum level
        }

        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        logEntry.put("level", level.toString());
        logEntry.put("component", component);
        logEntry.put("message", message);

        // Add thread-local context
        Map<String, Object> threadContext = context.get();
        if (!threadContext.isEmpty()) {
            logEntry.put("context", new LinkedHashMap<>(threadContext));
        }

        // Add structured data
        if (data != null && !data.isEmpty()) {
            logEntry.put("data", new LinkedHashMap<>(data));
        }

        // Add exception info
        if (throwable != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", throwable.getClass().getSimpleName());
            error.put("message", throwable.getMessage());
            // Stack trace is omitted to keep logs concise; can be added if needed
            logEntry.put("error", error);
        }

        // Add thread info for debugging
        logEntry.put("thread", Thread.currentThread().getName());

        // Output as JSON
        output.println(toJson(logEntry));
    }

    /**
     * Simple JSON serializer. Not a full implementation, but sufficient for log entries.
     * For production, consider using Jackson or Gson.
     */
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append(valueToJson(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private String valueToJson(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + escapeJson((String) value) + "\"";
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                sb.append(valueToJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        } else {
            return "\"" + escapeJson(value.toString()) + "\"";
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Creates a logger for a component.
     *
     * @param component the component name
     * @return the logger
     */
    public static StructuredLogger forComponent(String component) {
        return new StructuredLogger(component);
    }

    /**
     * Creates a logger for a component with a specific output stream.
     *
     * @param component the component name
     * @param output the output stream
     * @return the logger
     */
    public static StructuredLogger forComponent(String component, PrintStream output) {
        return new StructuredLogger(component, output);
    }
}
