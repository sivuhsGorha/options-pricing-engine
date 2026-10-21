package com.sbk.optionspricer.util;

import java.util.Set;

/**
 * Utility for safe error reporting that never exposes secrets.
 *
 * Rules:
 * - Never log exception messages that might contain URLs with tokens, API keys, or secrets
 * - Never log the full stack trace in production (can leak internal paths and potentially secrets)
 * - Never log environment variable values
 * - Never log configuration values that might be secrets
 * - Use only exception class name and sanitized descriptions
 */
public final class SafeErrorReporting {

    private static final Set<String> SECRET_KEYWORDS = Set.of(
            "key", "secret", "token", "password", "credential", "auth",
            "api_key", "api_key_id", "api_secret", "private_key", "access_token"
    );

    private SafeErrorReporting() {
        // utility class
    }

    /**
     * Returns a safe error description that never exposes secrets.
     *
     * @param throwable the exception
     * @param context optional context about what operation failed
     * @return a safe error message
     */
    public static String safeError(Throwable throwable, String context) {
        if (throwable == null) {
            return context != null ? context : "Unknown error";
        }

        String className = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();

        // Check if message might contain secrets
        if (message != null && mightContainSecret(message)) {
            return safeMessage(className, context, "message omitted (may contain secret)");
        }

        return safeMessage(className, context, message);
    }

    /**
     * Returns a safe error description without context.
     */
    public static String safeError(Throwable throwable) {
        return safeError(throwable, null);
    }

    /**
     * Returns a safe error description with only the exception type.
     * Use this when even the message might be sensitive.
     */
    public static String errorTypeOnly(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        return throwable.getClass().getSimpleName();
    }

    /**
     * Checks if a string might contain a secret based on keywords.
     */
    public static boolean mightContainSecret(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String keyword : SECRET_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a string by removing potential secrets.
     * If the string might contain secrets, returns a placeholder.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        if (mightContainSecret(text)) {
            return "[REDACTED: may contain secret]";
        }
        return text;
    }

    /**
     * Sanitizes a URL by removing query parameters that might contain secrets.
     */
    public static String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }
        int queryIndex = url.indexOf('?');
        if (queryIndex >= 0) {
            // Keep only the base URL, strip query parameters
            return url.substring(0, queryIndex) + "?[QUERY_PARAMS_REDACTED]";
        }
        return url;
    }

    /**
     * Sanitizes an environment variable name - never log the value.
     */
    public static String sanitizeEnvVar(String varName) {
        if (varName == null) {
            return null;
        }
        return varName; // It's safe to log the name, just not the value
    }

    /**
     * Formats a safe error message.
     */
    private static String safeMessage(String className, String context, String message) {
        StringBuilder sb = new StringBuilder();
        if (context != null && !context.isBlank()) {
            sb.append(context).append(": ");
        }
        sb.append(className);
        if (message != null && !message.isBlank()) {
            sb.append(" - ").append(message);
        }
        return sb.toString();
    }

    /**
     * Returns a safe description of an HTTP error that doesn't expose URLs with tokens.
     */
    public static String safeHttpError(int statusCode, String url, String method) {
        return String.format("HTTP %s %s failed with status %d", method, sanitizeUrl(url), statusCode);
    }

    /**
     * Returns a safe description of an HTTP error with exception.
     */
    public static String safeHttpError(int statusCode, String url, String method, Throwable throwable) {
        return safeHttpError(statusCode, url, method) + ": " + errorTypeOnly(throwable);
    }
}
