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
