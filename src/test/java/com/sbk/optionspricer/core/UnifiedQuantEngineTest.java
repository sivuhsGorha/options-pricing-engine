package com.sbk.optionspricer.core;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UnifiedQuantEngineTest {

    @Test
    void testEngineStopsOnException() throws Exception {
        UnifiedQuantEngine engine = new UnifiedQuantEngine();
        engine.start();

        // Inject a malicious NaN state to force an arithmetic exception
        // We can do this reflectively or by just letting it run if we had injected a bad mock.
        // For this test, we can inject a mock publisher that throws an exception.
        Field publisherField = UnifiedQuantEngine.class.getDeclaredField("publisher");
        publisherField.setAccessible(true);
        publisherField.set(engine, new MmapStatePublisher() {
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double spanMargin) {
                throw new RuntimeException("Simulated exception in loop");
            }
        });

        // Wait a bit for the loop to run
        Thread.sleep(500);

        // Verify the engine caught the exception and stopped
        Field isRunningField = UnifiedQuantEngine.class.getDeclaredField("isRunning");
        isRunningField.setAccessible(true);
        boolean isRunning = (boolean) isRunningField.get(engine);
        
        assertFalse(isRunning, "Engine should have stopped after encountering an exception in the loop");
    }
}
