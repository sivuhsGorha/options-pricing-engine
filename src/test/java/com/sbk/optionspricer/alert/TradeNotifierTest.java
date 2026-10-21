package com.sbk.optionspricer.alert;

import com.sbk.optionspricer.execution.VolSpreadStrategy.Decision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The phone is told when the engine trades, and only then; telling it never holds up, or breaks, a decision. */
class TradeNotifierTest {

    private record Sent(String title, String message, String priority, String tags) {}

    private static Decision decision(String action, List<String> orders) {
        return new Decision(Instant.parse("2026-10-20T15:13:58Z"), action, "market cheap to the SSVI reference", LocalDate.of(2026, 11, 20), 772.0,
                0.1358, 0.1419, -0.0062, 1, orders);
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Test
    void aDecisionThatSentOrdersIsReportedWithItsOrdersAndAHoldIsNot() throws Exception {
        List<Sent> sent = new CopyOnWriteArrayList<>();
        try (TradeNotifier notifier = new TradeNotifier((t, m, p, g) -> { sent.add(new Sent(t, m, p, g)); return true; })) {

            notifier.accept(decision("HOLD", List.of()));
            notifier.accept(decision("WAIT", List.of()));
            notifier.accept(decision("BUY_STRADDLE", List.of("BUY 3 SPY261120C00772000/SPY261120P00772000 MLEG filled")));

            assertTrue(waitFor(() -> sent.size() >= 1));
            Thread.sleep(200);
            assertEquals(1, sent.size(), "only the trade: " + sent);
            assertTrue(sent.get(0).title().contains("BUY_STRADDLE"), sent.toString());
            assertTrue(sent.get(0).message().contains("BUY 3 SPY261120C00772000/SPY261120P00772000 MLEG filled"), sent.get(0).message());
            assertTrue(sent.get(0).message().contains("772"), "the strike and the reason come with it: " + sent.get(0).message());
        }
    }

    @Test
    void aRefusedOrderIsFlaggedAsAWarningNotAsATrade() throws Exception {
        List<Sent> sent = new CopyOnWriteArrayList<>();
        try (TradeNotifier notifier = new TradeNotifier((t, m, p, g) -> { sent.add(new Sent(t, m, p, g)); return true; })) {

            notifier.accept(decision("BUY_STRADDLE_REJECTED", List.of("BUY 3 SPY261120C00772000/SPY261120P00772000 MLEG rejected: order failed pre-trade risk validation")));

            assertTrue(waitFor(() -> sent.size() == 1));
            assertEquals("warning", sent.get(0).tags(), sent.toString());
        }
    }

    @Test
    void aSlowOrFailingPhoneServiceNeverHoldsUpOrBreaksTheDecision() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> delivered = new ArrayList<>();
        try (TradeNotifier notifier = new TradeNotifier((t, m, p, g) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (delivered) { delivered.add(t); }
            throw new IllegalStateException("ntfy is down");
        })) {
            long start = System.nanoTime();

            notifier.accept(decision("CLOSE", List.of("SELL 3 X filled")));

            assertTrue((System.nanoTime() - start) / 1_000_000 < 500, "the decision thread must not wait for the phone service");
            release.countDown();
            assertTrue(waitFor(() -> { synchronized (delivered) { return delivered.size() == 1; } }), "it still tried");
            // and a failure inside the send did not kill the worker: the next trade is still sent
            notifier.accept(decision("CLOSE", List.of("SELL 1 X filled")));
            assertTrue(waitFor(() -> { synchronized (delivered) { return delivered.size() == 2; } }), "the worker survived the failure");
        }
    }
}
