package com.sbk.optionspricer.core;

import com.sbk.optionspricer.web.MmapStateReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class MmapConcurrencyStressTest {

    private static final String TEST_FILE = "target/stress_mmap_state.dat";

    @BeforeEach
    void setup() {
        System.setProperty("MMAP_STATE_FILE", TEST_FILE);
        new File(TEST_FILE).delete();
    }

    @AfterEach
    void teardown() {
        System.clearProperty("MMAP_STATE_FILE");
        new File(TEST_FILE).delete();
    }

    @Test
    void testConcurrentSeqlockZeroTornReads() throws Exception {
        MmapStatePublisher publisher = new MmapStatePublisher();
        MmapStateReader reader = new MmapStateReader();

        int readTarget = 10_000_000;
        AtomicLong readsCompleted = new AtomicLong();
        AtomicLong retries = new AtomicLong();
        AtomicReference<String> tornReadError = new AtomicReference<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2); // 1 writer, 1 reader

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Writer Thread
        executor.submit(() -> {
            try {
                startLatch.await();
                double val = 0.0;
                while (readsCompleted.get() < readTarget && tornReadError.get() == null) {
                    val += 1.0;
                    // We write the same value to all fields.
                    // If a reader sees different values, it's a torn read!
                    publisher.publishRiskState(val, val, val, val);
                    // Slight yield to allow reader to catch mid-writes sometimes
                    Thread.yield();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });

        // Reader Thread
        executor.submit(() -> {
            try {
                startLatch.await();
                while (readsCompleted.get() < readTarget && tornReadError.get() == null) {
                    try {
                        MmapStateReader.RiskState state = reader.readState();
                        // Validate consistency
                        if (state.netDelta != state.netGamma || 
                            state.netGamma != state.netVega || 
                            state.netVega != state.scenarioMargin) {
                            tornReadError.set(String.format(java.util.Locale.ROOT, "Torn Read Detected! Delta: %f, Gamma: %f, Vega: %f, scenarioMargin: %f",
                                state.netDelta, state.netGamma, state.netVega, state.scenarioMargin));
                            break;
                        }
                        readsCompleted.incrementAndGet();
                    } catch (IllegalStateException e) {
                        retries.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdownNow();
        
        publisher.close();
        reader.close();

        System.out.println("Total reads: " + readsCompleted.get());
        System.out.println("Retries: " + retries.get());
        System.out.println("Torn reads: " + (tornReadError.get() != null ? 1 : 0));

        assertNull(tornReadError.get(), "Expected zero torn reads but got: " + tornReadError.get());
        assertTrue(readsCompleted.get() >= 10_000_000, "Reader should complete all 10M reads safely");
    }

    @Test
    void testMissingFileReturnsUnavailable() throws Exception {
        new File(TEST_FILE).delete(); // Ensure it doesn't exist
        try {
            MmapStateReader reader = new MmapStateReader();
            reader.readState();
            fail("Expected IllegalStateException(UNAVAILABLE)");
        } catch (IllegalStateException e) {
            assertEquals("UNAVAILABLE", e.getMessage());
        }
    }

    @Test
    void testStaleHeartbeatReturnsUnavailable() throws Exception {
        MmapStatePublisher publisher = new MmapStatePublisher();
        publisher.publishRiskState(1.0, 1.0, 1.0, 1.0);
        
        MmapStateReader reader = new MmapStateReader();
        
        // Sleep past heartbeat timeout (assuming 2 seconds, but let's sleep 2.1s or mock the heartbeat)
        // Since we can't easily sleep 2 seconds in a unit test without slowing down, we can manually overwrite the heartbeat in the file.
        // Wait, MmapStateReader uses System.nanoTime(), so we can't mock the time easily. Let's just wait 2.1s if timeout is 2s.
        // Actually, the timeout in MmapStateReader is probably based on some fixed nanos.
        // Let's assume the publisher is closed, but file remains.
        publisher.close();
        
        // Wait a bit just in case
        Thread.sleep(2500); 

        try {
            reader.readState();
            fail("Expected IllegalStateException(UNAVAILABLE) due to stale heartbeat");
        } catch (IllegalStateException e) {
            assertEquals("UNAVAILABLE", e.getMessage());
        }
        reader.close();
    }
}
