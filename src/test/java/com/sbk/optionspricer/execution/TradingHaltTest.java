package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.GreekAlertManager;
import com.sbk.optionspricer.risk.GreekRiskMonitor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TradingHaltTest {

    private static MarketSnapshot liveSnapshot() {
        return new MarketSnapshot("SPY", 100.0, 100.1, 100.0, 2000L,
                Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static OrderManager manager(TradingHalt halt, AtomicInteger transmitted) {
        return new OrderManager(new PreTradeRiskFilter(100, 100000.0, 100),
                (order, sym, bid, ask) -> {
                    transmitted.incrementAndGet();
                    return new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");
                },
                new PositionTracker(), OrderManager.MarketDataPolicy.strict(), halt);
    }

    @Test
    void firstHaltReasonIsKeptAndResumeClearsIt() {
        TradingHalt halt = new TradingHalt();
        assertFalse(halt.isHalted());

        assertTrue(halt.halt("first"));
        assertFalse(halt.halt("second"), "a later alert must not overwrite the original cause");
        assertEquals("first", halt.reason().orElseThrow().message());

        halt.resume();
        assertFalse(halt.isHalted());
        assertTrue(halt.reason().isEmpty());
    }

    @Test
    void haltedManagerRejectsOrdersWithoutTouchingTheTransportUntilResumed() {
        TradingHalt halt = new TradingHalt();
        AtomicInteger transmitted = new AtomicInteger();
        OrderManager manager = manager(halt, transmitted);

        assertTrue(manager.submit(new Order(1, true, 10, 100.0), liveSnapshot()).accepted());

        halt.halt("test breach");
        OrderManager.OrderDecision rejected = manager.submit(new Order(1, true, 10, 100.0), liveSnapshot());
        assertFalse(rejected.accepted());
        assertEquals(OrderStatus.REJECTED, rejected.status());
        assertTrue(rejected.message().contains("halted"), rejected.message());
        assertEquals(1, transmitted.get(), "no order may reach the transport while halted");

        halt.resume();
        assertTrue(manager.submit(new Order(1, true, 10, 100.0), liveSnapshot()).accepted());
    }

    @Test
    void criticalGreekAlertTripsTheHaltButWarningDoesNot() {
        TradingHalt halt = new TradingHalt();
        GreekAlertManager alerts = new GreekAlertManager(50_000, 100_000, 5_000, 10_000, 300_000, 600_000);
        halt.haltOnCriticalAlerts(alerts);

        alerts.checkLimits(new GreekRiskMonitor.PortfolioRisk(60_000, 0, 0, 0, 0, 0, 0, 0));
        assertFalse(halt.isHalted(), "a WARNING must not halt trading");

        alerts.checkLimits(new GreekRiskMonitor.PortfolioRisk(150_000, 0, 0, 0, 0, 0, 0, 0));
        assertTrue(halt.isHalted());
        assertTrue(halt.reason().orElseThrow().message().contains("Net Delta"));
    }
}
