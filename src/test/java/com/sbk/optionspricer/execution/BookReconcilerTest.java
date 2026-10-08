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
}
