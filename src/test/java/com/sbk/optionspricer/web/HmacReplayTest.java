package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HmacReplayTest {

    private static final String SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();

    private static String sign(String method, String path, String query, String timestamp, String nonce) throws Exception {
        String payload = method + "\n" + path + "\n" + query + "\n" + timestamp + "\n" + nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean verify(String ts, String nonce) throws Exception {
        return HmacAuth.verify(SECRET, sign("GET", "/api/health", "", ts, nonce), "GET", "/api/health", "", ts, nonce);
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    @Test
    void concurrentReplaysOfOneValidRequestSucceedExactlyOnce() throws Exception {
        final int threads = 32;
        String ts = now();
        String nonce = "race-" + UUID.randomUUID();
        String signature = sign("GET", "/api/health", "", ts, nonce);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                barrier.await();
                return HmacAuth.verify(SECRET, signature, "GET", "/api/health", "", ts, nonce);
            }));
        }
        int accepted = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) accepted++;
        }
        pool.shutdownNow();

        assertEquals(1, accepted, "a signed request may be accepted once, however many copies race");
    }

    @Test
    void sequentialReplayIsRejected() throws Exception {
        String ts = now();
        String nonce = "seq-" + UUID.randomUUID();
        assertTrue(verify(ts, nonce));
        assertFalse(verify(ts, nonce));
    }

    @Test
    void nonceThatCouldAlterTheSignedPayloadLayoutIsRejected() throws Exception {
        String ts = now();
        // Even with a signature that is valid over this exact payload, the nonce itself is not acceptable.
        assertFalse(verify(ts, "abc\ndef"), "newline in nonce");
        assertFalse(verify(ts, "has space-" + UUID.randomUUID()), "space in nonce");
        assertFalse(verify(ts, "x".repeat(129)), "over-long nonce");
        assertFalse(verify(ts, ""), "empty nonce");
        assertTrue(verify(ts, "ok.nonce_value-" + UUID.randomUUID()), "ordinary nonce still works");
    }

    @Test
    void requestSignedBeforeThisProcessStartedIsRejectedEvenInsideTheTimeWindow() throws Exception {
        long startSeconds = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000;
        long uptimeSeconds = System.currentTimeMillis() / 1000 - startSeconds;
        assumeTrue(uptimeSeconds < 200, "needs the pre-start timestamp to still be inside the 300s window");

        // The nonce cache is in memory, so after a restart a captured request could be replayed.
        // Anything signed before this process started must therefore be refused.
        String beforeStart = String.valueOf(startSeconds - 60);
        assertFalse(verify(beforeStart, "pre-start-" + UUID.randomUUID()));
        assertTrue(verify(now(), "post-start-" + UUID.randomUUID()));
    }
}
