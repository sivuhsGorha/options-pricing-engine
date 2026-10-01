package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HmacAuthTest {

    @Test
    void testValidSignature() throws Exception {
        String secret = "super-secret-key";
        String method = "GET";
        String path = "/api/risk";
        String timestamp = "1630000000";
        String payload = method + path + timestamp;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

        assertTrue(HmacAuth.verify(secret, signature, method, path, timestamp), "Valid signature must pass");
    }

    @Test
    void testInvalidSignature() {
        String secret = "super-secret-key";
        assertFalse(HmacAuth.verify(secret, "bad-signature", "GET", "/api/risk", "1630000000"), "Invalid signature must fail");
    }
}
