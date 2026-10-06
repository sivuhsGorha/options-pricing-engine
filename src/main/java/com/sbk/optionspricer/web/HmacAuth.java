package com.sbk.optionspricer.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;

/**
 * HMAC-SHA256 request authentication for non-browser API clients.
 *
 * <p>A request is accepted at most once: its nonce is claimed atomically after the signature is
 * verified. The nonce cache is in memory, so requests signed before this process started are
 * refused outright, otherwise a captured request could be replayed after a restart.
 */
public class HmacAuth {

    /** Keeps the canonical payload unambiguous: no separators, whitespace or control characters. */
    private static final Pattern VALID_NONCE = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    private static final long MAX_PAST_SECONDS = 300;
    private static final long MAX_FUTURE_SECONDS = 30;
    /** Small allowance for client clocks running slightly behind this server's. */
    private static final long START_SKEW_SECONDS = 2;
    private static final long PROCESS_START_SECONDS = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000;

    private static final Cache<String, Boolean> usedNonces = CacheBuilder.newBuilder()
            .maximumSize(50000)
            .expireAfterWrite(11, TimeUnit.MINUTES) // must outlive the accepted timestamp window (past 300s + margin)
            .build();

    public static boolean verify(String secret, String signature, String method, String path, String query, String timestamp, String nonce) {
        return verify(secret, signature, method, path, query, timestamp, nonce, null);
    }

    public static boolean verify(String secret, String signature, String method, String path, String query, String timestamp, String nonce, byte[] body) {
        if (secret == null || signature == null || method == null || path == null || timestamp == null || nonce == null) {
            return false;
        }
        if (!VALID_NONCE.matcher(nonce).matches()) {
            return false;
        }
        try {
            long reqTime = Long.parseLong(timestamp);
            long timeNow = System.currentTimeMillis() / 1000;
            if (reqTime > timeNow + MAX_FUTURE_SECONDS || reqTime < timeNow - MAX_PAST_SECONDS) {
                return false;
            }
            if (reqTime < PROCESS_START_SECONDS - START_SKEW_SECONDS) {
                return false; // signed before this process (and its nonce memory) existed
            }
            String safeQuery = (query != null) ? query : "";
            String payload;
            if (body != null && body.length > 0) {
                String bodyDigest = Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(body));
                payload = method + "\n" + path + "\n" + safeQuery + "\n" + timestamp + "\n" + nonce + "\n" + bodyDigest;
            } else {
                payload = method + "\n" + path + "\n" + safeQuery + "\n" + timestamp + "\n" + nonce;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

            // Constant-time string comparison to prevent timing attacks
            boolean isValid = java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
            if (!isValid) {
                return false;
            }
            // Atomic claim: of any number of concurrent copies of one request, exactly one wins.
            return usedNonces.asMap().putIfAbsent(nonce, Boolean.TRUE) == null;
        } catch (Exception e) {
            return false;
        }
    }
}
