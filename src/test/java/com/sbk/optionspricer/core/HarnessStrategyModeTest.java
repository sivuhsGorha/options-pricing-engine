package com.sbk.optionspricer.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The harness steps the options strategy on its interval, only when enabled and selected. */
class HarnessStrategyModeTest {

    @Test
    void theOptionStrategyRunsOnItsIntervalOnlyInVolSpreadModeWithTheSwitchOn() {
        QuantSimulationHarness harness = new QuantSimulationHarness(null, null, null, null, null, null);
        AtomicInteger steps = new AtomicInteger();
        harness.setOptionStrategy(steps::incrementAndGet, Duration.ofSeconds(30));

        assertFalse(harness.stepOptionStrategyIfDue(1_000L), "default mode is momentum: the options strategy does not run");
        harness.setStrategyMode("vol_spread");
        assertTrue(harness.stepOptionStrategyIfDue(1_000L));
        assertFalse(harness.stepOptionStrategyIfDue(20_000L), "not due yet");
        assertTrue(harness.stepOptionStrategyIfDue(31_000L));
        assertEquals(2, steps.get());

        harness.setStrategyEnabled(false);
        assertFalse(harness.stepOptionStrategyIfDue(90_000L), "the operator switch stops it");
        assertEquals(2, steps.get());

        assertThrows(IllegalArgumentException.class, () -> harness.setStrategyMode("arbitrage"));
        assertThrows(IllegalArgumentException.class, () -> harness.setOptionStrategy(null, Duration.ofSeconds(1)));
        assertEquals("vol_spread", harness.getStrategyMode());
    }
}
