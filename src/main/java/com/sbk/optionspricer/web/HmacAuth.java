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
            .maximumSize(10000)
            .expireAfterWrite(11, TimeUnit.MINUTES) // TTL >= 2x timestamp window (10 mins) + some buffer
            .build();

    public static boolean verify(String secret, String signature, String method, String path, String timestamp, String nonce) {
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
            if (usedNonces.size() >= 10000) {
                return false; // Fail closed when cache is full
            }
            if (usedNonces.getIfPresent(nonce) != null) {
                return false; // Replayed nonce
            }
            usedNonces.put(nonce, Boolean.TRUE);

            String payload = method + path + timestamp + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            
            // Constant-time string comparison to prevent timing attacks
            return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
