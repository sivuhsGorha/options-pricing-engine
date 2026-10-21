package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** A book that differs from the Alpaca account halts trading and names the position; an unreachable account does not. */
class BookReconcilerTest {

    private static final String CALL = "SPY261120C00780000";

    private static PositionTracker book(int shares, int contracts) {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        if (shares != 0) tracker.applyFill(new PositionTracker.ExecutionFill("SPY", shares, 1, 777.1));
        if (contracts != 0) tracker.applyFill(new PositionTracker.ExecutionFill(CALL, contracts, 100, 9.9));
        return tracker;
    }

    private static BookReconciler reconciler(FakeAlpaca fake, PositionTracker tracker, TradingHalt halt) {
        return new BookReconciler(new AlpacaPaperClient(fake), tracker, halt, Duration.ofMinutes(1), Clock.systemUTC());
    }

    @Test
    void aMatchingBookIsOkAndShortOptionPositionsCompareBySign() {
        FakeAlpaca fake = new FakeAlpaca().positions(
                FakeAlpaca.positionJson("SPY", "long", "10", "us_equity", "777.1"),
                FakeAlpaca.positionJson(CALL, "short", "2", "us_option", "9.9"));
        TradingHalt halt = new TradingHalt();

        BookReconciler.Result r = reconciler(fake, book(10, -2), halt).reconcile();

        assertEquals("OK", r.status(), r.message());
        assertTrue(r.differences().isEmpty());
        assertFalse(halt.isHalted());
        assertTrue(r.message().contains("2 open positions"), r.message());
    }

    @Test
    void aDifferenceHaltsTradingNamingThePositionAndBothQuantities() {
        FakeAlpaca fake = new FakeAlpaca().positions(
                FakeAlpaca.positionJson("SPY", "long", "10", "us_equity", "777.1"),
                FakeAlpaca.positionJson(CALL, "short", "2", "us_option", "9.9"));
        TradingHalt halt = new TradingHalt();

        BookReconciler.Result r = reconciler(fake, book(10, 0), halt).reconcile();

        assertEquals("MISMATCH", r.status());
        assertEquals(java.util.List.of(CALL + ": local 0, alpaca -2"), r.differences());
        assertTrue(halt.isHalted());
        assertTrue(halt.reason().orElseThrow().message().contains(CALL + ": local 0, alpaca -2"), halt.reason().toString());
    }

    @Test
    void aPositionOnlyOnTheLocalSideIsAlsoAMismatch() {
        TradingHalt halt = new TradingHalt();

        BookReconciler.Result r = reconciler(new FakeAlpaca().positions(), book(10, 0), halt).reconcile();

        assertEquals("MISMATCH", r.status());
        assertEquals(java.util.List.of("SPY: local 10, alpaca 0"), r.differences());
        assertTrue(halt.isHalted());
    }

    @Test
    void resumingWithThePersistingDifferenceHaltsAgainAndFixingItReportsOk() {
        FakeAlpaca fake = new FakeAlpaca().positions();
        TradingHalt halt = new TradingHalt();
        PositionTracker tracker = book(10, 0);
        BookReconciler reconciler = reconciler(fake, tracker, halt);

        reconciler.reconcile();
        halt.resume();
        reconciler.reconcile();
        assertTrue(halt.isHalted(), "an operator cannot trade through a mismatch by resuming");

        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", -10, 1, 777.1)); // flattened locally to match
        BookReconciler.Result fixed = reconciler.reconcile();
        assertEquals("OK", fixed.status());
        assertTrue(halt.isHalted(), "the halt is not cleared automatically; the operator resumes once satisfied");
        assertSame(fixed, reconciler.lastResult());
    }

    @Test
    void anUnreachableAccountIsReportedWithoutHalting() {
        FakeAlpaca fake = new FakeAlpaca().positionsThrow(new UncheckedIOException(new IOException("connection timed out")));
        TradingHalt halt = new TradingHalt();

        BookReconciler.Result r = reconciler(fake, book(10, 0), halt).reconcile();

        assertEquals("UNREACHABLE", r.status());
        assertTrue(r.message().contains("connection timed out"), r.message());
        assertFalse(halt.isHalted());
    }

    @Test
    void aRejectedKeyIsReportedWithAlpacasMessage() {
        FakeAlpaca fake = new FakeAlpaca();
        fake.positions();
        AlpacaPaperClient.Http forbidden = (m, p, b) -> new AlpacaPaperClient.Response(403, "{\"code\":40110000,\"message\":\"forbidden.\"}");
        TradingHalt halt = new TradingHalt();

        BookReconciler.Result r = new BookReconciler(new AlpacaPaperClient(forbidden), book(0, 0), halt, Duration.ofMinutes(1), Clock.systemUTC()).reconcile();

        assertEquals("UNREACHABLE", r.status());
        assertTrue(r.message().contains("HTTP 403: forbidden."), r.message());
        assertFalse(halt.isHalted());
    }

    /**
     * Found live on 2026-10-20 at 15:22:43: the strategy's close sold 3 calls, Alpaca filled the sale at 15:22:42, the engine booked it
     * at 15:22:43.317, and the minute's reconciliation read the two books in between: local 3, alpaca 0. It halted trading, and the
     * halt stayed on for the rest of the session although the books agreed a second later. An order is in flight between Alpaca
     * filling it and the engine booking it, and a reconciliation must not read the books then.
     */
    @Test
    void aReconciliationWaitsForAnOrderInFlightAndNeverSeesAHalfBookedFill() throws Exception {
        PositionTracker tracker = book(10, 3); // the engine still holds the 3 calls: the sale is not booked yet
        FakeAlpaca fake = new FakeAlpaca().positions(FakeAlpaca.positionJson("SPY", "long", "10", "us_equity", "777.1")); // Alpaca: the calls are sold
        TradingHalt halt = new TradingHalt();
        Object orderLock = new Object();
        BookReconciler reconciler = reconciler(fake, tracker, halt);
        reconciler.serializeWith(orderLock);

        java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1);
        Thread order = new Thread(() -> {
            synchronized (orderLock) {
                inFlight.countDown();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                tracker.applyFill(new PositionTracker.ExecutionFill(CALL, -3, 100, 12.32)); // the booking, after Alpaca's fill
            }
        });
        order.start();
        assertTrue(inFlight.await(2, java.util.concurrent.TimeUnit.SECONDS));

        BookReconciler.Result r = reconciler.reconcile();
        order.join(2000);

        assertEquals("OK", r.status(), "read after the booking, not between the fill and the booking: " + r.message());
        assertFalse(halt.isHalted(), "a fill that is merely in flight is not a mismatch");
    }

    @Test
    void aRealMismatchIsStillCaughtWhenNoOrderIsInFlight() {
        PositionTracker tracker = book(10, 3);
        FakeAlpaca fake = new FakeAlpaca().positions(FakeAlpaca.positionJson("SPY", "long", "10", "us_equity", "777.1"));
        TradingHalt halt = new TradingHalt();
        BookReconciler reconciler = reconciler(fake, tracker, halt);
        reconciler.serializeWith(new Object());

        BookReconciler.Result r = reconciler.reconcile();

        assertEquals("MISMATCH", r.status());
        assertTrue(halt.isHalted());
    }
}
