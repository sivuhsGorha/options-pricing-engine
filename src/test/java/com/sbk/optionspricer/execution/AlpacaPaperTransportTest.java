package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The transport sends a day limit order at the touch, waits for the fill, and never returns with an order
 * still working at the venue. Everything here runs against recorded Alpaca responses, no network.
 */
class AlpacaPaperTransportTest {

    /** A clock that only moves when the transport sleeps. */
    static final class SteppingClock extends Clock {
        Instant now = Instant.parse("2026-10-08T14:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final String ID = "92983a34-4961-4125-84ce-3faddb66c601";
    private final SteppingClock clock = new SteppingClock();
    private int sleeps;

    private AlpacaPaperTransport transport(FakeAlpaca fake) {
        return new AlpacaPaperTransport(new AlpacaPaperClient(fake), Duration.ofSeconds(2), Duration.ofMillis(500), clock,
                d -> { sleeps++; clock.now = clock.now.plus(d); });
    }

    @Test
    void aBuyGoesAsADayLimitAtTheAskAndAnImmediateFillComesBackWithItsAveragePrice() {
        FakeAlpaca fake = new FakeAlpaca().post(200, FakeAlpaca.orderJson(ID, "filled", 10, 10, 777.31, "SPY", "buy"));

        ExecutionResult r = transport(fake).transmit(new Order(0, true, 10, 777.20), "spy", 777.10, 777.30);

        assertTrue(r.executed(), r.message());
        assertEquals(10, r.filledQuantity());
        assertEquals(777.31, r.executionPrice(), 1e-9);
        assertEquals("SPY", r.symbol());
        String request = fake.calls.get(0);
        assertTrue(request.startsWith("POST /v2/orders "), request);
        assertTrue(request.contains("\"symbol\":\"SPY\""), request);
        assertTrue(request.contains("\"side\":\"buy\""), request);
        assertTrue(request.contains("\"qty\":\"10\""), request);
        assertTrue(request.contains("\"limit_price\":\"777.30\""), "a buy is priced at the ask: " + request);
        assertTrue(request.contains("\"time_in_force\":\"day\""), request);
        assertTrue(request.contains("\"type\":\"limit\""), request);
        assertEquals(1, fake.calls.size(), "filled on submission: nothing to poll or cancel");
        assertEquals(0, sleeps);
    }

    @Test
    void aSellIsPricedAtTheBidAndAnOrderWithoutABookAtItsOwnPrice() {
        FakeAlpaca fake = new FakeAlpaca().post(200, FakeAlpaca.orderJson(ID, "filled", 3, 3, 9.85, "SPY261120C00780000", "sell"));
        transport(fake).transmit(new Order(0, false, 3, 9.9), "SPY261120C00780000", 9.85, 9.95);
        assertTrue(fake.calls.get(0).contains("\"side\":\"sell\"") && fake.calls.get(0).contains("\"limit_price\":\"9.85\""), fake.calls.get(0));

        assertEquals(777.12, AlpacaPaperTransport.limitPrice(new Order(0, true, 1, 777.123), Double.NaN, Double.NaN), 1e-9, "no book: the order's price, two decimals");
        assertEquals(777.30, AlpacaPaperTransport.limitPrice(new Order(0, true, 1, 777.123), 777.1, 777.3), 1e-9);
        assertEquals(777.10, AlpacaPaperTransport.limitPrice(new Order(0, false, 1, 777.123), 777.1, 777.3), 1e-9);
    }

    @Test
    void anOrderAcceptedThenFilledIsPolledUntilItFills() {
        FakeAlpaca fake = new FakeAlpaca()
                .post(200, FakeAlpaca.orderJson(ID, "pending_new", 10, 0, null, "SPY", "buy"))
                .getOrder(FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "filled", 10, 10, 777.25, "SPY", "buy"));

        ExecutionResult r = transport(fake).transmit(new Order(0, true, 10, 777.20), "SPY", 777.10, 777.30);

        assertTrue(r.executed());
        assertEquals(777.25, r.executionPrice(), 1e-9);
        assertEquals(2, fake.count("GET /v2/orders/" + ID));
        assertEquals(0, fake.count("DELETE"), "nothing to cancel once filled");
        assertTrue(r.message().contains(ID), "the venue's order id is in the message: " + r.message());
    }

    @Test
    void noFillWithinTheWaitCancelsTheOrderAndRejectsNamingTheWait() {
        FakeAlpaca fake = new FakeAlpaca()
                .post(200, FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"))
                .getOrder(FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "canceled", 10, 0, null, "SPY", "buy"));

        ExecutionResult r = transport(fake).transmit(new Order(0, true, 10, 777.20), "SPY", 777.10, 777.30);

        assertFalse(r.executed());
        assertEquals(0, r.filledQuantity());
        assertEquals(1, fake.count("DELETE /v2/orders/" + ID), "the remainder is cancelled before returning");
        assertTrue(r.message().contains("no fill within 2 s"), r.message());
        assertTrue(r.message().contains("remainder cancelled"), r.message());
        assertTrue(clock.now.isAfter(Instant.parse("2026-10-08T14:00:02Z")), "the wait elapsed on the clock");
    }

    @Test
    void aPartialFillAtTheDeadlineIsReportedAsPartialAndTheRemainderCancelled() {
        FakeAlpaca fake = new FakeAlpaca()
                .post(200, FakeAlpaca.orderJson(ID, "partially_filled", 3, 1, 9.9, "SPY261120C00780000", "sell"))
                .getOrder(FakeAlpaca.orderJson(ID, "partially_filled", 3, 1, 9.9, "SPY261120C00780000", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 3, 1, 9.9, "SPY261120C00780000", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 3, 1, 9.9, "SPY261120C00780000", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 3, 1, 9.9, "SPY261120C00780000", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 3, 1, 9.9, "SPY261120C00780000", "sell"),
                        FakeAlpaca.orderJson(ID, "canceled", 3, 1, 9.9, "SPY261120C00780000", "sell"));

        ExecutionResult r = transport(fake).transmit(new Order(0, false, 3, 9.9), "SPY261120C00780000", 9.85, 9.95);

        assertTrue(r.executed(), "what filled is booked");
        assertEquals(1, r.filledQuantity());
        assertEquals(9.9, r.executionPrice(), 1e-9);
        assertTrue(r.message().contains("partial fill 1 of 3"), r.message());
        assertEquals(1, fake.count("DELETE"));
    }

    @Test
    void theOrderManagerBooksAPartialFillAsPartiallyFilled() {
        FakeAlpaca fake = new FakeAlpaca()
                .post(200, FakeAlpaca.orderJson(ID, "partially_filled", 10, 4, 777.3, "SPY", "buy"))
                .getOrder(FakeAlpaca.orderJson(ID, "partially_filled", 10, 4, 777.3, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 4, 777.3, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 4, 777.3, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 4, 777.3, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 4, 777.3, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "canceled", 10, 4, 777.3, "SPY", "buy"));
        PositionTracker tracker = new PositionTracker(com.sbk.optionspricer.risk.FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000), transport(fake), tracker);

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 10, 777.2), new com.sbk.optionspricer.market.MarketSnapshot(
                "SPY", 777.1, 777.3, 777.2, 5000L, Instant.now(), Instant.now(), 0L, "FINNHUB", com.sbk.optionspricer.market.MarketDataStatus.LIVE));

        assertTrue(decision.accepted());
        assertEquals(OrderStatus.PARTIALLY_FILLED, decision.status());
        assertEquals(4, tracker.getNetQuantity("SPY"));
        assertEquals(777.3, tracker.getAverageCost("SPY"), 1e-9, "booked at Alpaca's average fill price");
    }

    @Test
    void anAlpacaRejectionComesBackVerbatimAndIsNotExecuted() {
        FakeAlpaca fake = new FakeAlpaca().post(403, "{\"code\":40310000,\"message\":\"insufficient options buying power\"}");

        ExecutionResult r = transport(fake).transmit(new Order(0, false, 3, 9.9), "SPY261120C00780000", 9.85, 9.95);

        assertFalse(r.executed());
        assertTrue(r.message().contains("HTTP 403: insufficient options buying power"), r.message());
        assertEquals(1, fake.calls.size(), "nothing to poll or cancel");
    }

    @Test
    void anUnreachableVenueIsNotExecutedAndTheMessageSaysSo() {
        FakeAlpaca fake = new FakeAlpaca().postThrows(new UncheckedIOException(new IOException("connection timed out")));

        ExecutionResult r = transport(fake).transmit(new Order(0, true, 10, 777.2), "SPY", 777.1, 777.3);

        assertFalse(r.executed());
        assertTrue(r.message().contains("Alpaca unreachable") && r.message().contains("connection timed out"), r.message());
    }

    @Test
    void aFailedCancelIsReportedBecauseTheOrderMayStillBeWorking() {
        FakeAlpaca fake = new FakeAlpaca()
                .post(200, FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"))
                .getOrder(FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"))
                .deleteThrows(new UncheckedIOException(new IOException("connection reset")));

        ExecutionResult r = transport(fake).transmit(new Order(0, true, 10, 777.2), "SPY", 777.1, 777.3);

        assertFalse(r.executed());
        assertTrue(r.message().contains("may still be working at Alpaca"), r.message());
        assertTrue(r.message().contains(ID), r.message());
    }

    @Test
    void theStatusCarriesTheLatestReconciliation() {
        AlpacaPaperTransport t = transport(new FakeAlpaca());

        TransportStatus pending = t.status(null);
        assertEquals("alpaca", pending.transport());
        assertEquals("PENDING", pending.reconciliation());
        assertTrue(pending.venue().contains("paper-api.alpaca.markets"));
        assertTrue(pending.description().contains("2 s"), pending.description());

        BookReconciler.Result mismatch = new BookReconciler.Result(clock.now, "MISMATCH", List.of("SPY: local 10, alpaca 0"), "book does not match");
        TransportStatus s = t.status(mismatch);
        assertEquals("MISMATCH", s.reconciliation());
        assertEquals(clock.now, s.checkedAt());
        assertEquals(List.of("SPY: local 10, alpaca 0"), s.differences());
    }

    // ---------------------------------------------------------------- the touch comes from the venue's own quote
    // Live on 2026-10-09: Finnhub's print lagged Alpaca's book by 30 to 50 cents in a moving market, so a buy limit
    // pegged to it sat below the bid and orders 2 to 5 of the session were cancelled unfilled. The venue's quote is
    // the market the order goes to, so it prices the touch when it is fresh.

    @Test
    void theTouchComesFromTheVenuesOwnQuoteWhenItIsFresh() {
        FakeAlpaca fake = new FakeAlpaca()
                .quote(FakeAlpaca.stockQuoteJson("SPY", 776.47, 776.50, "2026-10-08T13:59:58.780369414Z"))
                .post(200, FakeAlpaca.orderJson(ID, "filled", 10, 10, 776.50, "SPY", "buy"));

        ExecutionResult r = transport(fake).transmit(new Order(0, true, 10, 776.25), "SPY", 776.10, 776.30);

        assertTrue(r.executed(), r.message());
        assertEquals("GET /v2/stocks/SPY/quotes/latest?feed=iex", fake.dataCalls.get(0));
        assertTrue(fake.calls.get(0).contains("\"limit_price\":\"776.50\""), "a buy is priced at the venue's ask, not the feed's: " + fake.calls.get(0));

        FakeAlpaca sell = new FakeAlpaca()
                .quote(FakeAlpaca.stockQuoteJson("SPY", 776.47, 776.50, "2026-10-08T14:00:00Z"))
                .post(200, FakeAlpaca.orderJson(ID, "filled", 10, 10, 776.47, "SPY", "sell"));
        transport(sell).transmit(new Order(0, false, 10, 776.25), "SPY", 776.10, 776.30);
        assertTrue(sell.calls.get(0).contains("\"limit_price\":\"776.47\""), "a sell at the venue's bid: " + sell.calls.get(0));
    }

    @Test
    void aStaleOrUnavailableVenueQuoteFallsBackToTheFeedsBookAndTheMessageNamesThePriceSource() {
        FakeAlpaca stale = new FakeAlpaca()
                .quote(FakeAlpaca.stockQuoteJson("SPY", 776.47, 776.50, "2026-10-08T13:59:00Z"))
                .post(200, FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"))
                .getOrder(FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "canceled", 10, 0, null, "SPY", "buy"));

        ExecutionResult r = transport(stale).transmit(new Order(0, true, 10, 776.25), "SPY", 776.10, 776.30);

        assertTrue(stale.calls.get(0).contains("\"limit_price\":\"776.30\""), "a quote a minute old is not the touch: " + stale.calls.get(0));
        assertFalse(r.executed());
        assertTrue(r.message().contains("at limit 776.30 (the feed's ask)"), r.message());

        FakeAlpaca down = new FakeAlpaca()
                .quoteThrows(new UncheckedIOException(new IOException("connection timed out")))
                .post(200, FakeAlpaca.orderJson(ID, "filled", 10, 10, 776.25, "SPY", "buy"));
        transport(down).transmit(new Order(0, true, 10, 776.25), "SPY", Double.NaN, Double.NaN);
        assertTrue(down.calls.get(0).contains("\"limit_price\":\"776.25\""), "no quote anywhere: the order's own price: " + down.calls.get(0));

        FakeAlpaca none = new FakeAlpaca().post(200, FakeAlpaca.orderJson(ID, "filled", 10, 10, 776.30, "SPY", "buy"));
        transport(none).transmit(new Order(0, true, 10, 776.25), "SPY", 776.10, 776.30);
        assertEquals(1, none.dataCalls.size(), "the venue is asked once per order");
        assertTrue(none.calls.get(0).contains("\"limit_price\":\"776.30\""), none.calls.get(0));
    }

    @Test
    void anOptionIsQuotedFromTheOptionsFeedAndAStockFromIex() {
        FakeAlpaca fake = new FakeAlpaca().quote(FakeAlpaca.optionQuoteJson("SPY261120C00780000", 13.46, 13.78, "2026-10-09T13:41:35.711569063Z"));
        AlpacaPaperClient client = new AlpacaPaperClient(fake);

        AlpacaPaperClient.Quote q = client.latestQuote("SPY261120C00780000");

        assertEquals("GET /v1beta1/options/quotes/latest?symbols=SPY261120C00780000", fake.dataCalls.get(0));
        assertEquals(13.46, q.bid(), 1e-9);
        assertEquals(13.78, q.ask(), 1e-9);
        assertEquals(Instant.parse("2026-10-09T13:41:35.711569063Z"), q.at());

        FakeAlpaca stock = new FakeAlpaca().quote(FakeAlpaca.stockQuoteJson("SPY", 776.47, 776.50, "2026-10-09T13:41:35.780369414Z"));
        AlpacaPaperClient.Quote s = new AlpacaPaperClient(stock).latestQuote("spy");
        assertEquals("GET /v2/stocks/SPY/quotes/latest?feed=iex", stock.dataCalls.get(0));
        assertEquals(776.47, s.bid(), 1e-9);
        assertEquals(776.50, s.ask(), 1e-9);

        FakeAlpaca empty = new FakeAlpaca().quote("{\"quotes\":{}}");
        assertThrows(IllegalStateException.class, () -> new AlpacaPaperClient(empty).latestQuote("SPY261120C00780000"), "no quote is an error, never a zero");
        assertEquals("https://data.alpaca.markets", AlpacaPaperClient.DATA_BASE_URL);
    }

    // ---------------------------------------------------------------- the cancel race
    // Live on 2026-10-09: order 7 showed 9 of 10 filled at the deadline, the cancel was accepted, and the tenth share
    // filled 1.5 ms after the single confirmation fetch. The book said -19, Alpaca -20, and the reconciler halted
    // trading within the minute. The transport now waits for the order to settle before it reports.

    @Test
    void aFillThatCompletesWhileTheCancelIsInFlightIsBookedInFull() {
        FakeAlpaca fake = new FakeAlpaca()
                .post(200, FakeAlpaca.orderJson(ID, "partially_filled", 10, 9, 776.521111, "SPY", "sell"))
                .getOrder(FakeAlpaca.orderJson(ID, "partially_filled", 10, 9, 776.521111, "SPY", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 9, 776.521111, "SPY", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 9, 776.521111, "SPY", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 9, 776.521111, "SPY", "sell"),
                        FakeAlpaca.orderJson(ID, "partially_filled", 10, 9, 776.521111, "SPY", "sell"),
                        // after the cancel: still working, then the venue reports the whole order filled
                        FakeAlpaca.orderJson(ID, "pending_cancel", 10, 9, 776.521111, "SPY", "sell"),
                        FakeAlpaca.orderJson(ID, "pending_cancel", 10, 9, 776.521111, "SPY", "sell"),
                        FakeAlpaca.orderJson(ID, "filled", 10, 10, 776.526, "SPY", "sell"));

        ExecutionResult r = transport(fake).transmit(new Order(0, false, 10, 776.52), "SPY", 776.52, 776.55);

        assertTrue(r.executed());
        assertEquals(10, r.filledQuantity(), "the tenth share is booked, not left for the reconciler: " + r.message());
        assertEquals(776.526, r.executionPrice(), 1e-9, "at the venue's final average price");
        assertEquals(1, fake.count("DELETE /v2/orders/" + ID));
        assertTrue(r.message().contains("filled before the cancel took effect"), r.message());
    }

    @Test
    void aCancelThatDoesNotSettleWithinTheWaitIsReportedAsStillWorking() {
        FakeAlpaca fake = new FakeAlpaca()
                .post(200, FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"))
                .getOrder(FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "new", 10, 0, null, "SPY", "buy"),
                        FakeAlpaca.orderJson(ID, "pending_cancel", 10, 0, null, "SPY", "buy"));
        Instant before = clock.now;

        ExecutionResult r = transport(fake).transmit(new Order(0, true, 10, 777.2), "SPY", 777.1, 777.3);

        assertFalse(r.executed());
        assertTrue(r.message().contains("still reported as pending_cancel"), r.message());
        assertTrue(r.message().contains(ID), r.message());
        Duration waited = Duration.between(before, clock.now);
        assertTrue(waited.compareTo(Duration.ofSeconds(2).plus(AlpacaPaperTransport.CANCEL_SETTLE_WAIT)) >= 0, "waited for the cancel to settle: " + waited);
        assertTrue(waited.compareTo(Duration.ofSeconds(2).plus(AlpacaPaperTransport.CANCEL_SETTLE_WAIT).plusSeconds(1)) < 0, "but not forever: " + waited);
    }

    @Test
    void theClientOnlyKnowsThePaperEndpointAndNeedsBothKeys() {
        assertEquals("https://paper-api.alpaca.markets", AlpacaPaperClient.PAPER_BASE_URL);
        assertThrows(IllegalArgumentException.class, () -> new AlpacaPaperClient("", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new AlpacaPaperClient("key", " "));
    }
}
