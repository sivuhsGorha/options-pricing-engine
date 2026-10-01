package com.sbk.optionspricer.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class HmacAuth {

    public static boolean verify(String secret, String signature, String method, String path, String timestamp) {
        if (secret == null || signature == null || method == null || path == null || timestamp == null) {
            return false;
        }
        try {
            String payload = method + path + timestamp;
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
