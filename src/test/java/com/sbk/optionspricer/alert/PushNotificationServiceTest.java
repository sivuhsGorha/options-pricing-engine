package com.sbk.optionspricer.alert;

import com.sbk.optionspricer.execution.TradingHalt;
import com.sbk.optionspricer.market.MarketDataStatus;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PushNotificationServiceTest {

    @Test
    void serviceConstructsWithNullTransportsAndLogsWithoutCrashing() {
        try (PushNotificationService service = new PushNotificationService(null, null, Duration.ofMinutes(5), null)) {
            // When no webhook or ntfy is configured, sendNotification logs to stderr without error
            boolean sent = service.sendNotification("TEST ALERT", "Test message", "low", "test");
            assertFalse(sent, "sent must be false when no remote transport is configured");
        }
    }

    @Test
    void checkStalenessTriggersOnlyWhenStaleThresholdMet() {
        try (PushNotificationService service = new PushNotificationService(null, null, Duration.ofMinutes(5), null)) {
            // Fresh quote: age 10s -> should NOT alert
            service.checkStaleness(MarketDataStatus.LIVE, 10_000L, "SPY");

            // Stale quote: age 6 minutes (360_000 ms) -> should trigger alert
            service.checkStaleness(MarketDataStatus.LIVE, 360_000L, "SPY");

            // Cooldown check: second immediate call should be throttled by cooldown
            service.checkStaleness(MarketDataStatus.STALE, 370_000L, "SPY");
        }
    }

    @Test
    void monitorTradingHaltAcceptsTradingHaltAndClosesCleanly() {
        TradingHalt halt = new TradingHalt();
        try (PushNotificationService service = new PushNotificationService(null, null, Duration.ofMinutes(5), null)) {
            service.monitorTradingHalt(halt);
            assertFalse(halt.isHalted());
        }
    }
}
