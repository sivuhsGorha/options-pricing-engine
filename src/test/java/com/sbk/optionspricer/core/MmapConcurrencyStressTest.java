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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
                            state.netVega != state.spanMargin) {
                            tornReadError.set(String.format(
                                "Torn Read Detected! Delta: %f, Gamma: %f, Vega: %f, Margin: %f",
                                state.netDelta, state.netGamma, state.netVega, state.spanMargin));
                            break;
                        }
                        readsCompleted.incrementAndGet();
                    } catch (IllegalStateException e) {
                        // Unavailable is fine, just means we hit a retry limit or odd seq. We keep trying.
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

        assertNull(tornReadError.get(), "Expected zero torn reads but got: " + tornReadError.get());
        assertEquals(readTarget, readsCompleted.get(), "Reader should complete all 10M reads safely");
    }
}
