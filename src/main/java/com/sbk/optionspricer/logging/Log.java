package com.sbk.optionspricer.logging;

import java.util.Map;

/**
 * Unified logging facade that can output either structured JSON or the traditional
 * [TAG] message format, depending on configuration.
 *
 * This allows gradual migration from the old format to structured logging without
 * breaking existing code.
 *
 * Usage:
 *   Log.info("ENGINE", "Starting engine");
 *   Log.warn("STRATEGY", "Decision skipped", Map.of("reason", "stale data"));
 *   Log.error("ALERT", "Notification failed", exception);
 */
public final class Log {

    private static volatile boolean useStructuredLogging = false;
    private static volatile StructuredLogger.Level minLevel = StructuredLogger.Level.INFO;

    private Log() {
        // utility class
    }

    /**
     * Enables or disables structured JSON logging.
     * When disabled, falls back to traditional [TAG] message format.
     *
     * @param enabled true to enable structured logging
     */
    public static void setStructuredLogging(boolean enabled) {
        useStructuredLogging = enabled;
        if (enabled) {
            StructuredLogger.setGlobalMinLevel(minLevel);
        }
    }

    /**
     * Checks if structured logging is enabled.
     *
     * @return true if structured logging is enabled
     */
    public static boolean isStructuredLoggingEnabled() {
        return useStructuredLogging;
    }

    /**
     * Sets the minimum log level.
     *
     * @param level the minimum level
     */
    public static void setMinLevel(StructuredLogger.Level level) {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        minLevel = level;
        StructuredLogger.setGlobalMinLevel(level);
    }

    /**
     * Logs an INFO message.
     *
     * @param tag the tag (component name)
     * @param message the message
     */
    public static void info(String tag, String message) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag).info(message);
        } else {
            System.out.println("[" + tag + "] " + message);
        }
    }

    /**
     * Logs an INFO message with structured data.
     *
     * @param tag the tag (component name)
     * @param message the message
     * @param data the structured data
     */
    public static void info(String tag, String message, Map<String, Object> data) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag).info(message, data);
        } else {
            System.out.println("[" + tag + "] " + message + " " + formatData(data));
        }
    }

    /**
     * Logs a WARN message.
     *
     * @param tag the tag (component name)
     * @param message the message
     */
    public static void warn(String tag, String message) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag, System.err).warn(message);
        } else {
            System.err.println("[" + tag + "] " + message);
        }
    }

    /**
     * Logs a WARN message with structured data.
     *
     * @param tag the tag (component name)
     * @param message the message
     * @param data the structured data
     */
    public static void warn(String tag, String message, Map<String, Object> data) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag, System.err).warn(message, data);
        } else {
            System.err.println("[" + tag + "] " + message + " " + formatData(data));
        }
    }

    /**
     * Logs an ERROR message.
     *
     * @param tag the tag (component name)
     * @param message the message
     */
    public static void error(String tag, String message) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag, System.err).error(message);
        } else {
            System.err.println("[" + tag + "] " + message);
        }
    }

    /**
     * Logs an ERROR message with structured data.
     *
     * @param tag the tag (component name)
     * @param message the message
     * @param data the structured data
     */
    public static void error(String tag, String message, Map<String, Object> data) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag, System.err).error(message, data);
        } else {
            System.err.println("[" + tag + "] " + message + " " + formatData(data));
        }
    }

    /**
     * Logs an ERROR message with an exception.
     *
     * @param tag the tag (component name)
     * @param message the message
     * @param throwable the exception
     */
    public static void error(String tag, String message, Throwable throwable) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag, System.err).error(message, throwable);
        } else {
            System.err.println("[" + tag + "] " + message + ": " + throwable);
        }
    }

    /**
     * Logs an ERROR message with structured data and an exception.
     *
     * @param tag the tag (component name)
     * @param message the message
     * @param data the structured data
     * @param throwable the exception
     */
    public static void error(String tag, String message, Map<String, Object> data, Throwable throwable) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag, System.err).error(message, data, throwable);
        } else {
            System.err.println("[" + tag + "] " + message + " " + formatData(data) + ": " + throwable);
        }
    }

    /**
     * Logs a DEBUG message.
     *
     * @param tag the tag (component name)
     * @param message the message
     */
    public static void debug(String tag, String message) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag).debug(message);
        } else {
            // Debug logs are only printed in structured mode or if explicitly enabled
            // In traditional mode, debug is suppressed to avoid noise
        }
    }

    /**
     * Logs a DEBUG message with structured data.
     *
     * @param tag the tag (component name)
     * @param message the message
     * @param data the structured data
     */
    public static void debug(String tag, String message, Map<String, Object> data) {
        if (useStructuredLogging) {
            StructuredLogger.forComponent(tag).debug(message, data);
        } else {
            // Debug logs are only printed in structured mode
        }
    }

    /**
     * Formats structured data for traditional log output.
     */
    private static String formatData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Adds a context field that will be included in all logs in the current thread.
     * Only effective in structured logging mode.
     *
     * @param key the field key
     * @param value the field value
     */
    public static void addContext(String key, Object value) {
        StructuredLogger.addContext(key, value);
    }

    /**
     * Removes a context field.
     *
     * @param key the field key
     */
    public static void removeContext(String key) {
        StructuredLogger.removeContext(key);
    }

    /**
     * Clears all context fields for the current thread.
     */
    public static void clearContext() {
        StructuredLogger.clearContext();
    }
}
