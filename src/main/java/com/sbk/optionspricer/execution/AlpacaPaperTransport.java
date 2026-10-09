package com.sbk.optionspricer.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends orders to an Alpaca paper account and waits for the fill, so the synchronous
 * {@link ExchangeTransport} contract holds: when this returns, nothing is left working at the venue.
 *
 * <p>Each order goes as a day limit order at the touch: the ask for a buy, the bid for a sell. The touch is the
 * venue's own latest quote when it is fresh (within {@link #VENUE_QUOTE_MAX_AGE}), because that is the market the
 * order goes to; otherwise the feed's book, and without one the order's own price. The distinction matters: in
 * the first live session (2026-10-09) the spot feed's print lagged the venue by 30 to 50 cents in a moving market,
 * and a limit pegged to it sat outside the book and never filled. The result names which price was used.
 *
 * <p>The transport then polls the order until it is filled or otherwise done, or until {@code fillWait} has
 * passed, when it cancels the remainder and waits up to {@link #CANCEL_SETTLE_WAIT} for the order to settle
 * before reporting what filled, because a fill can complete while the cancel is in flight (it did, in the same
 * session: the tenth share filled 1.5 ms after a single confirmation fetch, and the reconciler had to halt
 * trading over a one-share mismatch). A partial fill is returned as such and the order manager marks it
 * {@code PARTIALLY_FILLED}; no fill is a rejection naming the wait and the limit. An Alpaca rejection (options
 * level, buying power, a closed market for an unsupported order) comes back verbatim.
 *
 * <p>If the cancel itself fails or does not settle in time, the order may still be working at Alpaca. The result
 * says so, and the {@link BookReconciler} will catch any later fill as a mismatch and halt trading.
 */
public final class AlpacaPaperTransport implements ExchangeTransport {

    static final Duration DEFAULT_FILL_WAIT = Duration.ofSeconds(10);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);
    /** A venue quote older than this is not the touch; the feed's book is used instead. */
    static final Duration VENUE_QUOTE_MAX_AGE = Duration.ofSeconds(30);
    /** After a cancel, how long the transport waits for the order to leave its working states before reporting. */
    static final Duration CANCEL_SETTLE_WAIT = Duration.ofSeconds(5);

    /** The two sides an order is priced from, and whose they are ("the venue's" or "the feed's"). */
    record Touch(double bid, double ask, String source) {}

    /** Pause between polls; replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final AlpacaPaperClient client;
    private final Duration fillWait;
    private final Duration pollInterval;
    private final Clock clock;
    private final Sleeper sleeper;
    private final AtomicLong sequence = new AtomicLong();
    private volatile ExecutionResult lastResult;
    private volatile String lastVenueOrderId;

    public AlpacaPaperTransport(AlpacaPaperClient client, Duration fillWait) {
        this(client, fillWait, DEFAULT_POLL_INTERVAL, Clock.systemUTC(), Thread::sleep);
    }

    AlpacaPaperTransport(AlpacaPaperClient client, Duration fillWait, Duration pollInterval, Clock clock, Sleeper sleeper) {
        if (client == null || fillWait == null || fillWait.isNegative() || fillWait.isZero() || pollInterval == null
                || pollInterval.isNegative() || pollInterval.isZero() || clock == null || sleeper == null) {
            throw new IllegalArgumentException("client, a positive fillWait and pollInterval, clock and sleeper are required");
        }
        this.client = client;
        this.fillWait = fillWait;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        String venueSymbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        Touch touch = touch(venueSymbol, bid, ask);
        double limit = limitPrice(order, touch.bid(), touch.ask());
        String limitText = String.format(Locale.ROOT, "%.2f", limit) + " (" + priceSource(order, touch) + ")";
        String clientOrderId = "aura-" + clock.millis() + "-" + sequence.incrementAndGet();

        AlpacaPaperClient.OrderState state;
        try {
            state = client.submitLimitOrder(venueSymbol, order.isBuy(), order.quantity(), limit, clientOrderId);
        } catch (AlpacaPaperClient.AlpacaException rejected) {
            return remember(new ExecutionResult(venueSymbol, 0, 0.0, false, "Alpaca rejected the order: " + rejected.getMessage()));
        } catch (RuntimeException unreachable) {
            return remember(new ExecutionResult(venueSymbol, 0, 0.0, false, "Alpaca unreachable: " + rootMessage(unreachable)));
        }
        lastVenueOrderId = state.id();

        Instant deadline = clock.instant().plus(fillWait);
        try {
            while (state.isWorking() && !clock.instant().isAfter(deadline)) {
                sleeper.sleep(pollInterval);
                state = client.order(state.id());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return remember(cancelAndReport(state, venueSymbol, order, "interrupted while waiting for the fill"));
        } catch (RuntimeException lost) {
            return remember(cancelAndReport(state, venueSymbol, order, "lost contact while waiting for the fill (" + rootMessage(lost) + ")"));
        }
        if (state.isWorking()) {
            return remember(cancelAndReport(state, venueSymbol, order, "no fill within " + fillWait.toSeconds() + " s at limit " + limitText));
        }
        return remember(report(state, venueSymbol, order, null));
    }

    /**
     * The venue's own quote when it is two-sided and fresh; otherwise the feed's book (which may be empty, in
     * which case {@link #limitPrice} falls back to the order's price). The venue's quote is a better price, not a
     * precondition: a failed read falls through.
     */
    Touch touch(String venueSymbol, double feedBid, double feedAsk) {
        try {
            AlpacaPaperClient.Quote q = client.latestQuote(venueSymbol);
            boolean twoSided = Double.isFinite(q.bid()) && q.bid() > 0.0 && Double.isFinite(q.ask()) && q.ask() >= q.bid();
            boolean fresh = q.at() != null && !q.at().isBefore(clock.instant().minus(VENUE_QUOTE_MAX_AGE));
            if (twoSided && fresh) {
                return new Touch(q.bid(), q.ask(), "the venue's");
            }
        } catch (RuntimeException unavailable) {
            // no venue quote: the feed's book prices the order, as before
        }
        return new Touch(feedBid, feedAsk, "the feed's");
    }

    /** Which price the limit came from, for the audit trail: "the venue's ask", "the feed's bid", "the order's price". */
    static String priceSource(Order order, Touch touch) {
        double side = order.isBuy() ? touch.ask() : touch.bid();
        if (!(Double.isFinite(side) && side > 0.0)) {
            return "the order's price";
        }
        return touch.source() + (order.isBuy() ? " ask" : " bid");
    }

    /**
     * Cancels what is left, waits for the order to settle (a fill can complete while the cancel is in flight) and
     * reports the final state; says so when the cancel could not be confirmed or the order is still working.
     */
    private ExecutionResult cancelAndReport(AlpacaPaperClient.OrderState state, String symbol, Order order, String why) {
        try {
            client.cancel(state.id());
            Instant settleBy = clock.instant().plus(CANCEL_SETTLE_WAIT);
            do {
                sleeper.sleep(pollInterval);
                state = client.order(state.id());
            } while (state.isWorking() && clock.instant().isBefore(settleBy));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return report(state, symbol, order, why + "; cancel sent but not confirmed, order " + state.id() + " may still be working at Alpaca");
        } catch (RuntimeException failed) {
            return report(state, symbol, order, why + "; cancel failed (" + rootMessage(failed) + "), order " + state.id()
                    + " may still be working at Alpaca: reconcile before trading");
        }
        if (state.isWorking()) {
            return report(state, symbol, order, why + "; cancel sent, order " + state.id() + " still reported as " + state.status() + " at Alpaca");
        }
        if (Math.round(state.filledQty()) >= order.quantity()) {
            return report(state, symbol, order, why + "; filled before the cancel took effect");
        }
        return report(state, symbol, order, why + "; remainder cancelled at Alpaca");
    }

    private static ExecutionResult report(AlpacaPaperClient.OrderState state, String symbol, Order order, String note) {
        int filled = (int) Math.round(state.filledQty());
        if (filled > 0 && Double.isFinite(state.filledAvgPrice()) && state.filledAvgPrice() > 0.0) {
            String message = filled == order.quantity()
                    ? "filled at Alpaca paper (order " + state.id() + ")"
                    : "partial fill " + filled + " of " + order.quantity() + " at Alpaca paper (order " + state.id() + ")";
            return new ExecutionResult(symbol, filled, state.filledAvgPrice(), true, note == null ? message : message + "; " + note);
        }
        String message = "Alpaca order " + state.id() + " " + state.status() + ", nothing filled";
        return new ExecutionResult(symbol, 0, 0.0, false, note == null ? message : message + ": " + note);
    }

    /** The touch: ask for a buy, bid for a sell; the order's own price without a book. Two decimals, as Alpaca requires. */
    static double limitPrice(Order order, double bid, double ask) {
        double reference;
        if (order.isBuy()) {
            reference = Double.isFinite(ask) && ask > 0.0 ? ask : order.price();
        } else {
            reference = Double.isFinite(bid) && bid > 0.0 ? bid : order.price();
        }
        return Math.round(reference * 100.0) / 100.0;
    }

    private ExecutionResult remember(ExecutionResult result) {
        lastResult = result;
        return result;
    }

    public ExecutionResult lastResult() {
        return lastResult;
    }

    public String lastVenueOrderId() {
        return lastVenueOrderId;
    }

    /** The status the dashboard shows, with the latest reconciliation when there is one. */
    public TransportStatus status(BookReconciler.Result reconciliation) {
        String description = "day limit orders at the venue's touch (its own latest quote; the feed's book when that is unavailable); waits up to "
                + fillWait.toSeconds() + " s for the fill, cancels the remainder and waits for the cancel to settle; the book is reconciled with the account every minute";
        if (reconciliation == null) {
            return new TransportStatus("alpaca", "Alpaca paper account (paper-api.alpaca.markets)", description, null, "PENDING", List.of());
        }
        return new TransportStatus("alpaca", "Alpaca paper account (paper-api.alpaca.markets)", description,
                reconciliation.at(), reconciliation.status(), reconciliation.differences());
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
