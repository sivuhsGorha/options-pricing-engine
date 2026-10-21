package com.sbk.optionspricer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeErrorReportingTest {

    @Test
    void testSafeErrorBasic() {
        Exception e = new RuntimeException("Something went wrong");
        String result = SafeErrorReporting.safeError(e);
        assertTrue(result.contains("RuntimeException"));
        assertTrue(result.contains("Something went wrong"));
    }

    @Test
    void testSafeErrorWithContext() {
        Exception e = new RuntimeException("Something went wrong");
        String result = SafeErrorReporting.safeError(e, "Operation failed");
        assertTrue(result.contains("Operation failed"));
        assertTrue(result.contains("RuntimeException"));
        assertTrue(result.contains("Something went wrong"));
    }

    @Test
    void testSafeErrorWithSecretInMessage() {
        Exception e = new RuntimeException("API key=abc123 failed");
        String result = SafeErrorReporting.safeError(e);
        assertTrue(result.contains("RuntimeException"));
        assertTrue(result.contains("message omitted"));
        assertFalse(result.contains("abc123"));
    }

    @Test
    void testSafeErrorWithNullThrowable() {
        String result = SafeErrorReporting.safeError(null, "Context");
        assertEquals("Context", result);
    }

    @Test
    void testSafeErrorWithNullContext() {
        Exception e = new RuntimeException("Error");
        String result = SafeErrorReporting.safeError(e, null);
        assertTrue(result.contains("RuntimeException"));
    }

    @Test
    void testErrorTypeOnly() {
        Exception e = new RuntimeException("Secret message");
        String result = SafeErrorReporting.errorTypeOnly(e);
        assertEquals("RuntimeException", result);
        assertFalse(result.contains("Secret message"));
    }

    @Test
    void testMightContainSecret() {
        assertTrue(SafeErrorReporting.mightContainSecret("api_key=123"));
        assertTrue(SafeErrorReporting.mightContainSecret("secret=abc"));
        assertTrue(SafeErrorReporting.mightContainSecret("token=xyz"));
        assertTrue(SafeErrorReporting.mightContainSecret("password=pass"));
        assertFalse(SafeErrorReporting.mightContainSecret("normal message"));
        assertFalse(SafeErrorReporting.mightContainSecret("error occurred"));
    }

    @Test
    void testSanitize() {
        assertEquals("[REDACTED: may contain secret]", SafeErrorReporting.sanitize("api_key=123"));
        assertEquals("normal message", SafeErrorReporting.sanitize("normal message"));
        assertNull(SafeErrorReporting.sanitize(null));
    }

    @Test
    void testSanitizeUrl() {
        assertEquals("https://example.com/api?[QUERY_PARAMS_REDACTED]",
                SafeErrorReporting.sanitizeUrl("https://example.com/api?key=123&token=abc"));
        assertEquals("https://example.com/api",
                SafeErrorReporting.sanitizeUrl("https://example.com/api"));
        assertNull(SafeErrorReporting.sanitizeUrl(null));
    }

    @Test
    void testSanitizeEnvVar() {
        assertEquals("API_KEY", SafeErrorReporting.sanitizeEnvVar("API_KEY"));
        assertNull(SafeErrorReporting.sanitizeEnvVar(null));
    }

    @Test
    void testSafeHttpError() {
        String result = SafeErrorReporting.safeHttpError(404, "https://api.example.com/data?key=123", "GET");
        assertTrue(result.contains("HTTP GET"));
        assertTrue(result.contains("404"));
        assertTrue(result.contains("[QUERY_PARAMS_REDACTED]"));
        assertFalse(result.contains("key=123"));
    }

    @Test
    void testSafeHttpErrorWithException() {
        Exception e = new RuntimeException("Network error");
        String result = SafeErrorReporting.safeHttpError(500, "https://api.example.com/data", "POST", e);
        assertTrue(result.contains("HTTP POST"));
        assertTrue(result.contains("500"));
        assertTrue(result.contains("RuntimeException"));
        assertFalse(result.contains("Network error"));
    }

    @Test
    void testSecretKeywordsCaseInsensitive() {
        assertTrue(SafeErrorReporting.mightContainSecret("API_KEY=123"));
        assertTrue(SafeErrorReporting.mightContainSecret("Api_Key=123"));
        assertTrue(SafeErrorReporting.mightContainSecret("API_KEY=123"));
    }

    @Test
    void testMultipleSecretKeywords() {
        assertTrue(SafeErrorReporting.mightContainSecret("api_key and secret both present"));
        assertTrue(SafeErrorReporting.mightContainSecret("token-based auth"));
    }
}
