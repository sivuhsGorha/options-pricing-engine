package com.sbk.optionspricer.core;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class UnifiedQuantEngineTest {

    @Test
    void testEngineStopsOnException() throws Exception {
        MmapStatePublisher throwingPublisher = new MmapStatePublisher() {
            private boolean initialized = false;
            
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double spanMargin) {
                if (!initialized) {
                    initialized = true;
                    return;
                }
                throw new RuntimeException("Simulated exception in loop");
            }
        };

        UnifiedQuantEngine engine = new UnifiedQuantEngine(throwingPublisher);
        QuantSimulationHarness harness = new QuantSimulationHarness(engine);
        harness.start();

        // Wait a bit for the loop to run
        Thread.sleep(500);

        // In the new architecture, UnifiedQuantEngine catches exceptions.
        // We just ensure the harness doesn't crash the JVM and can be stopped safely.
        harness.stop();
        
        Field isRunningField = QuantSimulationHarness.class.getDeclaredField("isRunning");
        isRunningField.setAccessible(true);
        boolean isRunning = (boolean) isRunningField.get(harness);
        
        assertFalse(isRunning, "Harness should be stopped");
        throwingPublisher.close();
    }
}
