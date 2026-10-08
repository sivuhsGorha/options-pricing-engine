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
 * <p>Each order goes as a day limit order at the touch (the ask for a buy, the bid for a sell; the order's
 * own price when there is no book), the same price the in-process simulator fills at before slippage. The
 * transport then polls the order until it is filled or otherwise done, or until {@code fillWait} has passed,
 * when it cancels the remainder and reports what filled: a partial fill is returned as such and the order
 * manager marks it {@code PARTIALLY_FILLED}; no fill is a rejection naming the wait. An Alpaca rejection
 * (options level, buying power, a closed market for an unsupported order) comes back verbatim.
 *
 * <p>If the cancel itself fails, the order may still be working at Alpaca. The result says so, and the
 * {@link BookReconciler} will catch any later fill as a mismatch and halt trading.
 */
public final class AlpacaPaperTransport implements ExchangeTransport {

    static final Duration DEFAULT_FILL_WAIT = Duration.ofSeconds(10);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

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
        double limit = limitPrice(order, bid, ask);
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
            return remember(cancelAndReport(state, venueSymbol, order,
                    "no fill within " + fillWait.toSeconds() + " s at limit " + String.format(Locale.ROOT, "%.2f", limit)));
        }
        return remember(report(state, venueSymbol, order, null));
    }

    /** Cancels what is left, fetches the final state and reports it; says so when the cancel could not be confirmed. */
    private ExecutionResult cancelAndReport(AlpacaPaperClient.OrderState state, String symbol, Order order, String why) {
        try {
            client.cancel(state.id());
            sleeper.sleep(pollInterval);
            state = client.order(state.id());
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
        String description = "day limit orders at the touch; waits up to " + fillWait.toSeconds()
                + " s for the fill and cancels the remainder; the book is reconciled with the account every minute";
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
