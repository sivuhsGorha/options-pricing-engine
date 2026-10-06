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
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

public final class BrowserSessionManager {
    public static final String COOKIE_NAME = "OPTIONS_SESSION";
    public static final int SESSION_MAX_AGE_SECONDS = 900;
    public static final long SESSION_IDLE_TIMEOUT_MILLIS = 600_000L; // 10 minutes
    private static final long LOGIN_WINDOW_MILLIS = 60_000L;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int DEFAULT_PBKDF2_ITERATIONS = 100_000;
    private static final int DEFAULT_MAX_SESSIONS = 1_000;
    private static final int KEY_LENGTH_BITS = 256;
    /** Above this many tracked clients, expired login windows are swept on every attempt. */
    private static final int LOGIN_SWEEP_THRESHOLD = 256;
    /** Hard ceiling on tracked clients; beyond it new clients are refused rather than remembered. */
    private static final int MAX_TRACKED_LOGIN_CLIENTS = 50_000;
    /** At most this many password hashes run at once, so a flood of logins cannot occupy every server thread. */
    private static final int MAX_CONCURRENT_HASHES = 2;

    private final byte[] passwordSalt;
    private final byte[] operatorPasswordHash;
    private final Set<String> allowedOrigins;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginWindow> loginWindows = new ConcurrentHashMap<>();
    private final Semaphore hashSlots = new Semaphore(MAX_CONCURRENT_HASHES);
    private final LongSupplier clock;
    private final int pbkdf2Iterations;
    private final int maxSessions;

    public BrowserSessionManager(String operatorPassword, String allowedOrigin) {
        this(operatorPassword, allowedOrigin, System::currentTimeMillis, DEFAULT_PBKDF2_ITERATIONS, DEFAULT_MAX_SESSIONS);
    }

    /** Package-private: lets tests control the clock, hash cost and session cap. */
    BrowserSessionManager(String operatorPassword, String allowedOrigin, LongSupplier clock, int pbkdf2Iterations, int maxSessions) {
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
        if (pbkdf2Iterations < 1 || maxSessions < 1) {
            throw new IllegalArgumentException("pbkdf2Iterations and maxSessions must be positive.");
        }
        this.allowedOrigins = origins;
        this.clock = clock;
        this.pbkdf2Iterations = pbkdf2Iterations;
        this.maxSessions = maxSessions;
        this.passwordSalt = new byte[16];
        this.secureRandom.nextBytes(this.passwordSalt);
        this.operatorPasswordHash = pbkdf2(operatorPassword.toCharArray(), this.passwordSalt, pbkdf2Iterations);
    }

    public boolean isAllowedOrigin(String origin) {
        if (origin == null) return false;
        return allowedOrigins.contains(origin);
    }

    public LoginResult login(String suppliedPassword, InetSocketAddress remoteAddress) {
        return login(suppliedPassword, ClientAddressResolver.resolve(remoteAddress, null, Set.of()));
    }

    /** @param clientKey the address used for rate limiting (see {@link ClientAddressResolver}) */
    public LoginResult login(String suppliedPassword, String clientKey) {
        long now = clock.getAsLong();
        sweepExpired(now);

        if (loginWindows.size() >= MAX_TRACKED_LOGIN_CLIENTS && !loginWindows.containsKey(clientKey)) {
            return new LoginResult(true, null); // fail closed instead of growing without bound
        }
        LoginWindow window = loginWindows.compute(clientKey, (key, current) -> {
            if (current == null || now - current.startedAt >= LOGIN_WINDOW_MILLIS) {
                return new LoginWindow(now, 1);
            }
            return new LoginWindow(current.startedAt, current.attempts + 1);
        });
        if (window.attempts > MAX_LOGIN_ATTEMPTS) {
            return new LoginResult(true, null);
        }

        if (!hashSlots.tryAcquire()) {
            return new LoginResult(true, null); // too many hashes already running
        }
        boolean passwordMatches;
        try {
            char[] candidateChars = suppliedPassword == null ? new char[0] : suppliedPassword.toCharArray();
            byte[] candidate = pbkdf2(candidateChars, passwordSalt, pbkdf2Iterations);
            passwordMatches = MessageDigest.isEqual(operatorPasswordHash, candidate);
        } finally {
            hashSlots.release();
        }
        if (!passwordMatches) {
            return new LoginResult(false, null);
        }

        if (sessions.size() >= maxSessions) {
            return new LoginResult(true, null); // table full of live sessions: refuse rather than evict someone
        }
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        long expiresAt = now + SESSION_MAX_AGE_SECONDS * 1000L;
        sessions.put(token, new SessionRecord(now, expiresAt, now));
        return new LoginResult(false, token);
    }

    /** Removes expired login windows and sessions so neither table grows with idle or abandoned entries. */
    private void sweepExpired(long now) {
        if (loginWindows.size() > LOGIN_SWEEP_THRESHOLD) {
            loginWindows.entrySet().removeIf(e -> now - e.getValue().startedAt >= LOGIN_WINDOW_MILLIS);
        }
        sessions.entrySet().removeIf(e -> isExpired(e.getValue(), now));
    }

    private static boolean isExpired(SessionRecord record, long now) {
        return now >= record.expiresAt || now - record.lastAccessedAt >= SESSION_IDLE_TIMEOUT_MILLIS;
    }

    public boolean isValidSessionCookie(String cookieHeader) {
        String token = getSessionToken(cookieHeader);
        if (token == null) return false;
        SessionRecord record = sessions.get(token);
        if (record == null) return false;
        long now = clock.getAsLong();
        if (isExpired(record, now)) {
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

    int sessionCount() {
        return sessions.size();
    }

    int trackedLoginClients() {
        return loginWindows.size();
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
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
