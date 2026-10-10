package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found on review (2026-10-10): the dashboard reads the audit trail under the same lock an order holds while it
 * waits for the venue, so with the Alpaca transport (up to 15 s per order) the order tape stopped answering
 * whenever an order was in flight.
 */
class OrderManagerAuditLockTest {

    private static MarketSnapshot quote() {
        Instant now = Instant.now();
        return new MarketSnapshot("SPY", 776.40, 776.50, 776.45, 5000L, now, now, 0L, "TEST", MarketDataStatus.LIVE);
    }

    @Test
    void theAuditTrailAnswersWhileAnOrderIsWaitingForTheVenue() throws Exception {
        CountDownLatch atVenue = new CountDownLatch(1);
        CountDownLatch venueMayAnswer = new CountDownLatch(1);
        ExchangeTransport slowVenue = (order, sym, bid, ask) -> {
            atVenue.countDown();
            try {
                venueMayAnswer.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");
        };
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1_000, 1e9, 1_000), slowVenue, tracker);

        // One finished order, so the trail has something to show.
        CompletableFuture<OrderManager.OrderDecision> inFlight = CompletableFuture.supplyAsync(
                () -> manager.submit(new Order(0, true, 1, 776.45), quote()));
        assertTrue(atVenue.await(5, TimeUnit.SECONDS), "the order reached the venue");

        CompletableFuture<List<OrderManager.ExecutionAuditRecord>> read = CompletableFuture.supplyAsync(manager::getAuditTrail);
        List<OrderManager.ExecutionAuditRecord> trail;
        try {
            trail = read.get(2, TimeUnit.SECONDS);
        } finally {
            venueMayAnswer.countDown();
        }

        assertNotNull(trail, "the read returned while the order was still at the venue");
        assertTrue(inFlight.get(5, TimeUnit.SECONDS).accepted());
        assertEquals(1, manager.getAuditTrail().size(), "and the finished order is on the trail afterwards");
    }
}
