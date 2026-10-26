package com.sbk.optionspricer.core;

import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.TradingHalt;
import com.sbk.optionspricer.risk.GreekAlertManager;
import com.sbk.optionspricer.risk.GreekRiskMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Theta and rho are aggregated over the book and, when {@code risk.max_theta} / {@code risk.max_rho} are set, alert
 * and halt like the other first-order Greeks. Unset, they are shown but never alert: a default limit chosen
 * without data could halt a book that is fine.
 */
class ThetaRhoAlertTest {

    @AfterEach
    void clearState() {
        System.clearProperty("MMAP_STATE_FILE");
    }

    private static GreekRiskMonitor.PortfolioRisk risk(double theta, double rho) {
        return new GreekRiskMonitor.PortfolioRisk(0, 0, 0, 0, 0, 0, 0, 0, theta, rho);
    }

    @Test
    void withoutThetaAndRhoLimitsTheManagerNeverAlertsOnThem() {
        GreekAlertManager alerts = new GreekAlertManager(4000, 5000, 800, 1000, 8000, 10000);
        List<GreekAlertManager.GreekAlert> fired = new ArrayList<>();
        alerts.addAlertListener(fired::add);

        alerts.checkLimits(risk(-1e9, 1e9));

        assertFalse(alerts.monitorsTheta());
        assertFalse(alerts.monitorsRho());
        assertEquals(Double.POSITIVE_INFINITY, alerts.thetaCritical());
        assertTrue(fired.isEmpty(), fired.toString());
    }

    @Test
    void aThetaOrRhoLimitWarnsAtEightyPercentAndIsCriticalAtTheLimit() {
        GreekAlertManager alerts = new GreekAlertManager(4000, 5000, 800, 1000, 8000, 10000, 800, 1000, 40_000, 50_000);
        List<GreekAlertManager.GreekAlert> fired = new ArrayList<>();
        alerts.addAlertListener(fired::add);

        alerts.checkLimits(risk(-850, 0));
        assertEquals(1, fired.size(), fired.toString());
        assertEquals("Net Theta", fired.get(0).metric());
        assertEquals(GreekAlertManager.AlertSeverity.WARNING, fired.get(0).severity(), "theta decays, so its sign is negative: the limit is on the magnitude");

        alerts.checkLimits(risk(-1200, 60_000));
        assertEquals(3, fired.size(), fired.toString());
        assertEquals(GreekAlertManager.AlertSeverity.CRITICAL, fired.get(1).severity());
        assertEquals("Net Rho", fired.get(2).metric());
        assertEquals(GreekAlertManager.AlertSeverity.CRITICAL, fired.get(2).severity());
    }

    @Test
    void aNaNThresholdSwitchesThatGreekOffRatherThanAlertingOnEverything() {
        GreekAlertManager alerts = new GreekAlertManager(4000, 5000, 800, 1000, 8000, 10000, Double.NaN, Double.NaN, 40_000, 50_000);
        List<GreekAlertManager.GreekAlert> fired = new ArrayList<>();
        alerts.addAlertListener(fired::add);

        alerts.checkLimits(risk(-1e6, 0));

        assertFalse(alerts.monitorsTheta());
        assertTrue(alerts.monitorsRho());
        assertTrue(fired.isEmpty(), "NaN compares false with everything; it must mean off, not always on: " + fired);
    }

    @Test
    void aConfiguredThetaLimitHaltsTradingAndAnAbsentOneDoesNot() throws Exception {
        System.setProperty("MMAP_STATE_FILE", "target/theta-rho-alert-test-state.dat");
        Path withTheta = Files.createTempFile("theta", ".yaml");
        Files.writeString(withTheta, "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\n"
                + "risk:\n  max_delta: 5000.0\n  max_gamma: 300.0\n  max_vega: 20000.0\n  max_theta: 1000.0\n");
        Path without = Files.createTempFile("notheta", ".yaml");
        Files.writeString(without, "market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\n"
                + "risk:\n  max_delta: 5000.0\n  max_gamma: 300.0\n  max_vega: 20000.0\n");
        AtomicInteger exits = new AtomicInteger();
        MmapStatePublisher publisher = new MmapStatePublisher();
        try {
            UnifiedQuantEngine engine = UnifiedQuantEngine.fromConfig(ConfigManager.fromFile(withTheta), publisher, code -> exits.incrementAndGet());
            assertEquals(1000.0, engine.getGreekAlertManager().thetaCritical(), 1e-9, "critical at risk.max_theta");
            assertEquals(800.0, engine.getGreekAlertManager().thetaWarning(), 1e-9, "warning at 80% of it");
            assertFalse(engine.getGreekAlertManager().monitorsRho(), "risk.max_rho is not set");

            TradingHalt halt = new TradingHalt();
            halt.haltOnCriticalAlerts(engine.getGreekAlertManager());
            engine.setExposureSource(() -> new PositionTracker.PortfolioExposure(0.0, 0.0, 0.0, 0.0, -900.0, 0.0));
            engine.processTick(100.0);
            assertFalse(halt.isHalted(), "900 of theta against a 1,000 limit is a WARNING, not a halt");

            engine.setExposureSource(() -> new PositionTracker.PortfolioExposure(0.0, 0.0, 0.0, 0.0, -1200.0, 0.0));
            engine.processTick(100.0);
            assertTrue(halt.isHalted(), "a book beyond risk.max_theta halts trading");
            assertTrue(halt.reason().orElseThrow().message().contains("Theta"), halt.reason().toString());

            UnifiedQuantEngine unlimited = UnifiedQuantEngine.fromConfig(ConfigManager.fromFile(without), publisher, code -> exits.incrementAndGet());
            TradingHalt stillTrading = new TradingHalt();
            stillTrading.haltOnCriticalAlerts(unlimited.getGreekAlertManager());
            unlimited.setExposureSource(() -> new PositionTracker.PortfolioExposure(0.0, 0.0, 0.0, 0.0, -1e9, 1e9));
            unlimited.processTick(100.0);
            assertFalse(stillTrading.isHalted(), "without a configured limit theta and rho are shown, not enforced");
            assertEquals(0, exits.get(), "a limit breach is a halt, never a fatal exit");
        } finally {
            publisher.close();
        }
    }

    @Test
    void aThetaOrRhoLimitMustBePositiveWhenGiven() {
        System.setProperty("MMAP_STATE_FILE", "target/theta-rho-alert-bad-limit-state.dat");
        MmapStatePublisher publisher = new MmapStatePublisher();
        try {
            UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, code -> { });
            assertThrows(IllegalArgumentException.class, () -> engine.configureGreekAlerts(1.0, 1.0, 1.0, -5.0, Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> engine.configureGreekAlerts(1.0, 1.0, 1.0, Double.NaN, 0.0));
            assertThrows(IllegalArgumentException.class, () -> engine.configureGreekAlerts(1.0, 1.0, 1.0, Double.POSITIVE_INFINITY, 1.0));
            engine.configureGreekAlerts(1.0, 1.0, 1.0, Double.NaN, Double.NaN); // unset is allowed
            assertFalse(engine.getGreekAlertManager().monitorsTheta());
        } finally {
            publisher.close();
        }
    }
}
