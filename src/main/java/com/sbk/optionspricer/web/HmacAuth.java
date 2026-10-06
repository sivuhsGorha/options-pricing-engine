package com.sbk.optionspricer.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;

public class HmacAuth {

    private static final Cache<String, Boolean> usedNonces = CacheBuilder.newBuilder()
            .maximumSize(50000)
            .expireAfterWrite(11, TimeUnit.MINUTES) // TTL >= 2x timestamp window (10 mins) + buffer
            .build();

    public static boolean verify(String secret, String signature, String method, String path, String query, String timestamp, String nonce) {
        return verify(secret, signature, method, path, query, timestamp, nonce, null);
    }

    public static boolean verify(String secret, String signature, String method, String path, String query, String timestamp, String nonce, byte[] body) {
        if (secret == null || signature == null || method == null || path == null || timestamp == null || nonce == null) {
            return false;
        }
        try {
            long reqTime = Long.parseLong(timestamp);
            long timeNow = System.currentTimeMillis() / 1000;
            // Cap future skew at 30s, and past at 300s
            if (reqTime > timeNow + 30 || reqTime < timeNow - 300) {
                return false;
            }
            if (usedNonces.getIfPresent(nonce) != null) {
                return false; // Replayed nonce
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
            if (isValid) {
                usedNonces.put(nonce, Boolean.TRUE);
            }
            return isValid;
        } catch (Exception e) {
            return false;
        }
    }
}
