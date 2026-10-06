package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OrderLifecycleTest {

    private static MarketSnapshot liveSpy() {
        return new MarketSnapshot("SPY", 100.0, 100.1, 100.0, 2000L,
                Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static OrderManager manager(ExchangeTransport transport, PositionTracker tracker, TradingHalt halt) {
        return new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000), transport, tracker,
                OrderManager.MarketDataPolicy.strict(), halt);
    }

    private static ExchangeTransport fillsFraction(double fraction) {
        return (order, sym, bid, ask) -> new ExecutionResult(sym, (int) Math.round(order.quantity() * fraction),
                order.price(), true, "Executed");
    }

    @Test
    void fullyFilledOrderIsFilledAndNotLeftOpen() {
        OrderManager manager = manager(fillsFraction(1.0), new PositionTracker(), new TradingHalt());

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 10, 100.0), liveSpy());

        assertTrue(decision.accepted());
        assertEquals(OrderStatus.FILLED, decision.status());
        assertEquals(0, manager.getOpenOrders().size(), "a filled order is no longer working");
        assertEquals(OrderStatus.FILLED, manager.getOrderStatus(decision.orderId()).orElseThrow());
    }

    @Test
    void partialFillStaysOpenUntilCancelledAndKeepsTheFilledPart() {
        PositionTracker tracker = new PositionTracker();
        OrderManager manager = manager(fillsFraction(0.4), tracker, new TradingHalt());

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 10, 100.0), liveSpy());

        assertTrue(decision.accepted());
        assertEquals(OrderStatus.PARTIALLY_FILLED, decision.status());
        assertEquals(4, tracker.getNetQuantity("SPY"));
        assertEquals(1, manager.getOpenOrders().size());

        manager.cancel(decision.orderId());

        assertEquals(0, manager.getOpenOrders().size());
        assertEquals(OrderStatus.CANCELLED, manager.getOrderStatus(decision.orderId()).orElseThrow());
        assertEquals(4, tracker.getNetQuantity("SPY"), "cancelling the remainder must not undo executed quantity");
    }

    @Test
    void cancellingAFilledOrderFailsAndUnknownOrdersAreRejected() {
        OrderManager manager = manager(fillsFraction(1.0), new PositionTracker(), new TradingHalt());
        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 10, 100.0), liveSpy());

        assertThrows(IllegalStateException.class, () -> manager.cancel(decision.orderId()));
        assertThrows(IllegalArgumentException.class, () -> manager.cancel(999_999L));
        assertEquals(OrderStatus.FILLED, manager.getOrderStatus(decision.orderId()).orElseThrow());
    }

    @Test
    void rejectedOrdersAreTrackedAsRejected() {
        OrderManager manager = manager(fillsFraction(1.0), new PositionTracker(), new TradingHalt());

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, -5, 100.0), liveSpy());

        assertFalse(decision.accepted());
        assertEquals(OrderStatus.REJECTED, manager.getOrderStatus(decision.orderId()).orElseThrow());
    }

    @Test
    void transportReportingAnImpossibleFillIsNotBookedAndHaltsTrading() {
        PositionTracker tracker = new PositionTracker();
        TradingHalt halt = new TradingHalt();
        OrderManager manager = manager(
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity() * 3, order.price(), true, "bogus"),
                tracker, halt);

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 10, 100.0), liveSpy());

        assertFalse(decision.accepted());
        assertEquals(0, tracker.getNetQuantity("SPY"), "an inconsistent fill report must not be booked");
        assertTrue(halt.isHalted(), "an unreconcilable exchange response must stop trading");
    }

    @Test
    void bookingFailureAfterExecutionHaltsTradingInsteadOfThrowing() {
        PositionTracker tracker = new PositionTracker((symbol, qty, multiplier) -> {
            throw new RuntimeException("disk full");
        });
        TradingHalt halt = new TradingHalt();
        OrderManager manager = manager(fillsFraction(1.0), tracker, halt);

        OrderManager.OrderDecision decision = assertDoesNotThrow(
                () -> manager.submit(new Order(1, true, 10, 100.0), liveSpy()));

        assertTrue(decision.accepted(), "the order did execute at the venue; the caller must know");
        assertTrue(decision.message().contains("booking failed"), decision.message());
        assertTrue(halt.isHalted());
        assertFalse(manager.submit(new Order(1, true, 1, 100.0), liveSpy()).accepted(),
                "no further orders until the position is reconciled");
        assertTrue(manager.getAuditTrail().stream().anyMatch(r -> r.accepted() && r.rejectionReason().contains("BOOKING_FAILED")));
    }
}
