package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.LiquidityRiskMonitor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrderManagerTest {

    @Test
    void acceptsValidOrderAndTracksLifecycle() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        OrderManager manager = new OrderManager(filter, (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), new PositionTracker());

        Order order = new Order(1, true, 10, 101.50);
        com.sbk.optionspricer.market.MarketSnapshot snapshot = new com.sbk.optionspricer.market.MarketSnapshot("SPY", 100.0, 100.1, 100.0, 2000L, java.time.Instant.now(), java.time.Instant.now(), 0L, "LIVE", com.sbk.optionspricer.market.MarketDataStatus.LIVE);
        OrderManager.OrderDecision decision = manager.submit(order, snapshot);

        assertTrue(decision.accepted());
        assertEquals(OrderStatus.ACCEPTED, decision.status());
        assertEquals(1, manager.getOpenOrders().size());
    }

    private static OrderManager managerWith(OrderManager.MarketDataPolicy policy, java.util.concurrent.atomic.AtomicInteger transmitted) {
        return new OrderManager(new PreTradeRiskFilter(100, 100000.0, 100),
                (order, sym, bid, ask) -> {
                    transmitted.incrementAndGet();
                    return new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");
                },
                new PositionTracker(), policy);
    }

    private static com.sbk.optionspricer.market.MarketSnapshot snapshot(com.sbk.optionspricer.market.MarketDataStatus status, long ageMs) {
        return new com.sbk.optionspricer.market.MarketSnapshot("SPY", 100.0, 100.1, 100.0, 2000L,
                java.time.Instant.now(), java.time.Instant.now(), ageMs, "TEST", status);
    }

    @Test
    void strictPolicyRejectsStaleUnavailableAndSimulatedData() {
        for (com.sbk.optionspricer.market.MarketDataStatus status : new com.sbk.optionspricer.market.MarketDataStatus[] {
                com.sbk.optionspricer.market.MarketDataStatus.STALE,
                com.sbk.optionspricer.market.MarketDataStatus.UNAVAILABLE,
                com.sbk.optionspricer.market.MarketDataStatus.SIMULATED}) {
            java.util.concurrent.atomic.AtomicInteger transmitted = new java.util.concurrent.atomic.AtomicInteger();
            OrderManager manager = managerWith(OrderManager.MarketDataPolicy.strict(), transmitted);

            OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 10, 100.0), snapshot(status, 0L));

            assertFalse(decision.accepted(), status + " must not be tradable");
            assertEquals(OrderStatus.REJECTED, decision.status());
            assertEquals(0, transmitted.get(), "nothing may reach the exchange transport on " + status);
            assertEquals(0, manager.getOpenOrders().size());
            assertEquals(status, manager.getAuditTrail().get(0).sourceStatus());
        }
    }

    @Test
    void strictPolicyAcceptsLiveAndDelayedButRejectsOldQuotes() {
        java.util.concurrent.atomic.AtomicInteger transmitted = new java.util.concurrent.atomic.AtomicInteger();
        OrderManager manager = managerWith(OrderManager.MarketDataPolicy.strict(), transmitted);

        assertTrue(manager.submit(new Order(1, true, 10, 100.0), snapshot(com.sbk.optionspricer.market.MarketDataStatus.LIVE, 100L)).accepted());
        assertTrue(manager.submit(new Order(1, true, 10, 100.0), snapshot(com.sbk.optionspricer.market.MarketDataStatus.DELAYED, 100L)).accepted());
        assertFalse(manager.submit(new Order(1, true, 10, 100.0),
                snapshot(com.sbk.optionspricer.market.MarketDataStatus.LIVE, OrderManager.MarketDataPolicy.strict().maxQuoteAgeMs() + 1)).accepted());
        assertEquals(2, transmitted.get());
    }

    @Test
    void allowSimulatedPolicyAcceptsSimulatedButNeverStaleOrUnavailable() {
        java.util.concurrent.atomic.AtomicInteger transmitted = new java.util.concurrent.atomic.AtomicInteger();
        OrderManager manager = managerWith(OrderManager.MarketDataPolicy.allowSimulated(), transmitted);

        assertTrue(manager.submit(new Order(1, true, 10, 100.0), snapshot(com.sbk.optionspricer.market.MarketDataStatus.SIMULATED, 0L)).accepted());
        assertFalse(manager.submit(new Order(1, true, 10, 100.0), snapshot(com.sbk.optionspricer.market.MarketDataStatus.STALE, 0L)).accepted());
        assertFalse(manager.submit(new Order(1, true, 10, 100.0), snapshot(com.sbk.optionspricer.market.MarketDataStatus.UNAVAILABLE, 0L)).accepted());
        assertEquals(1, transmitted.get());
    }

    @Test
    void rejectsInvalidOrderBeforeRouting() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        OrderManager manager = new OrderManager(filter, (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), new PositionTracker());

        com.sbk.optionspricer.market.MarketSnapshot snapshot = new com.sbk.optionspricer.market.MarketSnapshot("SPY", 100.0, 100.1, 100.0, 2000L, java.time.Instant.now(), java.time.Instant.now(), 0L, "LIVE", com.sbk.optionspricer.market.MarketDataStatus.LIVE);
        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, -5, 100.0), snapshot);

        assertFalse(decision.accepted());
        assertEquals(OrderStatus.REJECTED, decision.status());
        assertEquals(0, manager.getOpenOrders().size());
    }

    @Test
    void rejectsOrderWhenConcentrationOrLiquidityRiskIsBreached() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(
                1000,
                1_000_000.0,
                100,
                Map.of(1, "SPY"),
                new ConcentrationLimitManager(Map.of("SPY", 500.0)),
                new LiquidityRiskMonitor(25.0, 1000L)
        );
        OrderManager manager = new OrderManager(filter, (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), new PositionTracker());

        Order order = new Order(1, true, 100, 100.0);
        com.sbk.optionspricer.market.MarketSnapshot snapshot = new com.sbk.optionspricer.market.MarketSnapshot("SPY", 100.0, 100.1, 100.0, 2000L, java.time.Instant.now(), java.time.Instant.now(), 0L, "LIVE", com.sbk.optionspricer.market.MarketDataStatus.LIVE);
        OrderManager.OrderDecision decision = manager.submit(order, snapshot);

        assertFalse(decision.accepted());
        assertEquals(OrderStatus.REJECTED, decision.status());
        assertEquals(0, manager.getOpenOrders().size());
    }
}
