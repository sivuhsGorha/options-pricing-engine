package com.sbk.optionspricer.web;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BrowserSessionManager {
    public static final String COOKIE_NAME = "OPTIONS_SESSION";
    public static final int SESSION_MAX_AGE_SECONDS = 900;
    public static final long SESSION_IDLE_TIMEOUT_MILLIS = 600_000L; // 10 minutes
    private static final long LOGIN_WINDOW_MILLIS = 60_000L;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final byte[] passwordSalt;
    private final byte[] operatorPasswordHash;
    private final Set<String> allowedOrigins;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginWindow> loginWindows = new ConcurrentHashMap<>();

    public BrowserSessionManager(String operatorPassword, String allowedOrigin) {
        if (operatorPassword == null || operatorPassword.isEmpty()) {
            throw new IllegalArgumentException("OPERATOR_PASSWORD must be configured.");
        }
        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            throw new IllegalArgumentException("ALLOWED_ORIGIN must be configured.");
        }
        Set<String> origins = java.util.Arrays.stream(allowedOrigin.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (origins.contains("*")) {
            throw new IllegalArgumentException("Wildcard '*' in ALLOWED_ORIGIN is not permitted when credentialed sessions are enabled.");
        }
        this.allowedOrigins = origins;
        this.passwordSalt = new byte[16];
        this.secureRandom.nextBytes(this.passwordSalt);
        this.operatorPasswordHash = pbkdf2(operatorPassword.toCharArray(), this.passwordSalt);
    }

    public boolean isAllowedOrigin(String origin) {
        if (origin == null) return false;
        return allowedOrigins.contains(origin);
    }

    public LoginResult login(String suppliedPassword, InetSocketAddress remoteAddress) {
        String clientKey = remoteAddress == null ? "unknown" : remoteAddress.getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        LoginWindow window = loginWindows.compute(clientKey, (key, current) -> {
            if (current == null || now - current.startedAt >= LOGIN_WINDOW_MILLIS) {
                return new LoginWindow(now, 1);
            }
            return new LoginWindow(current.startedAt, current.attempts + 1);
        });
        if (window.attempts > MAX_LOGIN_ATTEMPTS) {
            return new LoginResult(true, null);
        }

        char[] candidateChars = suppliedPassword == null ? new char[0] : suppliedPassword.toCharArray();
        byte[] candidate = pbkdf2(candidateChars, passwordSalt);
        if (!MessageDigest.isEqual(operatorPasswordHash, candidate)) {
            return new LoginResult(false, null);
        }

        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        long expiresAt = now + SESSION_MAX_AGE_SECONDS * 1000L;
        sessions.put(token, new SessionRecord(now, expiresAt, now));
        return new LoginResult(false, token);
    }

    public boolean isValidSessionCookie(String cookieHeader) {
        String token = getSessionToken(cookieHeader);
        if (token == null) return false;
        SessionRecord record = sessions.get(token);
        if (record == null) return false;
        long now = System.currentTimeMillis();
        if (now >= record.expiresAt || now - record.lastAccessedAt >= SESSION_IDLE_TIMEOUT_MILLIS) {
            sessions.remove(token, record);
            return false;
        }
        // Update idle access time
        sessions.replace(token, record, new SessionRecord(record.createdAt, record.expiresAt, now));
        return true;
    }

    public boolean logout(String cookieHeader) {
        String token = getSessionToken(cookieHeader);
        if (token == null) return false;
        return sessions.remove(token) != null;
    }

    public static String getSessionToken(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            String prefix = COOKIE_NAME + "=";
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length());
            }
        }
        return null;
    }

    private static byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to calculate PBKDF2 hash", e);
        }
    }

    public record LoginResult(boolean rateLimited, String sessionToken) {}

    private record LoginWindow(long startedAt, int attempts) {}

    private record SessionRecord(long createdAt, long expiresAt, long lastAccessedAt) {}
}
