package com.sbk.optionspricer.core;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.models.pde.DiscreteDividendPricer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class PdeConcurrencyTest {

    @Test
    public void testPdeWorkspaceConcurrency() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Preallocate a single workspace for all threads to expose race conditions
        DiscreteDividendPricer.Workspace sharedWorkspace = new DiscreteDividendPricer.Workspace(150);

        // Calculate expected price single-threaded
        double expectedPrice = DiscreteDividendPricer.price(
                OptionType.CALL, new OptionParameters(100.0, 100.0, 1.0, 0.05, 0.20, 0.0), null, 150, 150, true);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        double price = DiscreteDividendPricer.price(
                                OptionType.CALL, new OptionParameters(100.0, 100.0, 1.0, 0.05, 0.20, 0.0), null, 150, 150, true);
                        if (Math.abs(price - expectedPrice) > 1e-9) {
                            System.err.println("Race condition detected! Expected " + expectedPrice + ", got " + price);
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errorCount.get(), "Race condition occurred when sharing a single workspace across threads!");
    }
}
