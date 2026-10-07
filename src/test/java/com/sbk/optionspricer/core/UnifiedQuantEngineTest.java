package com.sbk.optionspricer.core;

import com.sbk.optionspricer.web.MmapStateReader;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

public class UnifiedQuantEngineTest {

    @Test
    void testEngineStopsOnException() throws Exception {
        System.setProperty("MMAP_STATE_FILE", "target/fatal_test_state.dat");
        
        java.util.concurrent.atomic.AtomicInteger unavailableCount = new java.util.concurrent.atomic.AtomicInteger(0);
        MmapStatePublisher throwingPublisher = new MmapStatePublisher() {
            private boolean initialized = false;
            
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
                if (!initialized) {
                    initialized = true;
                    return;
                }
                throw new RuntimeException("Simulated exception in loop");
            }
            
            @Override
            public void publishUnavailable() {
                unavailableCount.incrementAndGet();
                super.publishUnavailable();
            }
        };

        java.util.concurrent.atomic.AtomicInteger exitCode = new java.util.concurrent.atomic.AtomicInteger(0);
        UnifiedQuantEngine engine = new UnifiedQuantEngine(throwingPublisher, exitCode::set);
        // No keys and no network: the default provider would read the developer's API keys and call the providers.
        com.sbk.optionspricer.web.LiveSpotProvider offline = new com.sbk.optionspricer.web.LiveSpotProvider(
                null, null, null, null, (url, headers) -> { throw new java.io.IOException("no network in tests"); });
        QuantSimulationHarness harness = new QuantSimulationHarness(engine, offline);
        harness.start();

        for (int i = 0; i < 50; i++) {
            if (engine.getState() == UnifiedQuantEngine.EngineState.STOPPED_FATAL) {
                break;
            }
            Thread.sleep(100);
        }
        assertDoesNotThrow(() -> harness.stop(), "The exception 'Already closed' never occurs");
        
        assertEquals(UnifiedQuantEngine.EngineState.STOPPED_FATAL, engine.getState(), "Engine should be STOPPED_FATAL");
        assertEquals(1, exitCode.get(), "System exit handler should be invoked with code 1");
        assertEquals(1, unavailableCount.get(), "The fatal handler runs exactly once");
        
        MmapStateReader reader = new MmapStateReader();
        try {
            reader.readState();
            fail("Should have thrown IllegalStateException(UNAVAILABLE) due to invalidated magic");
        } catch (IllegalStateException e) {
            assertEquals("UNAVAILABLE", e.getMessage(), "Published snapshot should be UNAVAILABLE");
        }


        reader.close();
    }

    @Test
    void testEngineProcessExitCodeNonZero() throws Exception {
        // Run a small main method in a separate process that triggers the crash
        ProcessBuilder pb = new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp", System.getProperty("java.class.path"),
            FatalCrashRunner.class.getName()
        );
        Process p = pb.start();
        int exitCode = p.waitFor();
        assertTrue(exitCode != 0, "Process exit code should be non-zero on fatal crash");
    }

    public static class FatalCrashRunner {
        public static void main(String[] args) {
            MmapStatePublisher throwingPublisher = new MmapStatePublisher() {
                @Override
                public void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
                    throw new RuntimeException("Simulated exception in loop");
                }
            };
            UnifiedQuantEngine engine = new UnifiedQuantEngine(throwingPublisher);
            engine.processTick(100.0);
        }
    }
}

