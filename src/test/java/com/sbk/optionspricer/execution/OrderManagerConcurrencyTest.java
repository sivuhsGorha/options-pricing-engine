package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class OrderManagerConcurrencyTest {

    private static MarketSnapshot liveSpy() {
        return new MarketSnapshot("SPY", 100.0, 100.1, 100.0, 2000L,
                Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    @Test
    void concurrentSubmissionsNeverBreachThePortfolioLimit() throws Exception {
        final int limit = 100;
        final int threads = 16;
        final int ordersPerThread = 20;

        PositionTracker tracker = new PositionTracker();
        // Only delta binds: 1 share = 1 delta, so the book may hold at most `limit` shares.
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1e12, limit, 1e12, 1e12, 1e12);
        OrderManager manager = new OrderManager(
                new PreTradeRiskFilter(1000, 1e12, 1_000_000),
                (order, sym, bid, ask) -> {
                    LockSupport.parkNanos(50_000); // widen the window between admission and fill
                    return new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed");
                },
                tracker, OrderManager.MarketDataPolicy.strict(), new TradingHalt(), admission);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < ordersPerThread; i++) {
                    if (manager.submit(new Order(1, true, 1, 100.0), liveSpy()).accepted()) {
                        accepted.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(limit, accepted.get(), "exactly `limit` one-share buys fit under the delta limit");
        assertEquals(limit, tracker.getNetQuantity("SPY"), "the book must never exceed the limit");
        assertEquals(limit, manager.getOpenOrders().size());
    }
}
