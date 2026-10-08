package com.sbk.optionspricer.core;

import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.TradingHalt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The alerts that halt trading use the configured risk limits, not constants of their own. */
class GreekAlertConfigTest {

    @AfterEach
    void clearState() {
        System.clearProperty("MMAP_STATE_FILE");
    }

    @Test
    void thresholdsComeFromTheConfiguredLimitsAndABreachHaltsTrading() throws Exception {
        System.setProperty("MMAP_STATE_FILE", "target/greek-alert-config-test-state.dat");
        Path yaml = Files.createTempFile("alerts", ".yaml");
        Files.writeString(yaml, "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\nrisk:\n  max_delta: 5000.0\n  max_gamma: 300.0\n  max_vega: 20000.0\n");
        AtomicInteger exits = new AtomicInteger();
        MmapStatePublisher publisher = new MmapStatePublisher();
        try {
            UnifiedQuantEngine engine = UnifiedQuantEngine.fromConfig(ConfigManager.fromFile(yaml), publisher, code -> exits.incrementAndGet());

            assertEquals(5000.0, engine.getGreekAlertManager().deltaCritical(), 1e-9, "critical at the configured limit");
            assertEquals(4000.0, engine.getGreekAlertManager().deltaWarning(), 1e-9, "warning at 80% of it");
            assertEquals(300.0, engine.getGreekAlertManager().gammaCritical(), 1e-9);
            assertEquals(20000.0, engine.getGreekAlertManager().vegaCritical(), 1e-9);

            TradingHalt halt = new TradingHalt();
            halt.haltOnCriticalAlerts(engine.getGreekAlertManager());
            engine.setExposureSource(() -> new PositionTracker.PortfolioExposure(4500.0, 0.0, 0.0, 450_000.0));
            engine.processTick(100.0);
            assertFalse(halt.isHalted(), "4,500 delta is a WARNING, not a halt");

            engine.setExposureSource(() -> new PositionTracker.PortfolioExposure(-5200.0, 0.0, 0.0, 520_000.0));
            engine.processTick(100.0);
            assertTrue(halt.isHalted(), "a book beyond risk.max_delta halts trading");
            assertTrue(halt.reason().orElseThrow().message().contains("Delta"), halt.reason().toString());
            assertEquals(0, exits.get(), "a limit breach is a halt, never a fatal exit");
        } finally {
            publisher.close();
        }
    }

    @Test
    void theDefaultsMatchTheConfigDefaultsAndBadLimitsAreRejected() {
        System.setProperty("MMAP_STATE_FILE", "target/greek-alert-default-test-state.dat");
        MmapStatePublisher publisher = new MmapStatePublisher();
        try {
            UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, code -> { });
            assertEquals(ConfigManager.DEFAULT_MAX_DELTA, engine.getGreekAlertManager().deltaCritical(), 1e-9);
            assertEquals(UnifiedQuantEngine.WARNING_FRACTION * ConfigManager.DEFAULT_MAX_VEGA, engine.getGreekAlertManager().vegaWarning(), 1e-9);
            assertThrows(IllegalArgumentException.class, () -> engine.configureGreekAlerts(0.0, 1.0, 1.0));
        } finally {
            publisher.close();
        }
    }
}
