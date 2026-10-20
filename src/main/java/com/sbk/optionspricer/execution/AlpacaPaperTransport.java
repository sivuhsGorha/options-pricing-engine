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
    /** How far past the touch a limit is set, in basis points: 0 is the touch itself. See {@link #setLimitCushionBps}. */
    private volatile double limitCushionBps = 0.0;
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

    /** Largest cushion accepted: past this a limit is no longer a protection but an invitation to pay anything. */
    static final double MAX_LIMIT_CUSHION_BPS = 500.0;

    /**
     * Widens every limit past the touch by this many basis points: a buy is raised and a sell lowered, rounded to the cent in the
     * direction that cannot leave the limit short of the touch. A marketable limit still fills at the market, so this is a
     * ceiling on what is paid (a floor on what is received), not a price chased. It exists because a limit exactly on an
     * indicative quote often misses the real market by a few cents. Zero, the default, sets the limit at the touch.
     */
    public void setLimitCushionBps(double bps) {
        if (!Double.isFinite(bps) || bps < 0.0 || bps > MAX_LIMIT_CUSHION_BPS) {
            throw new IllegalArgumentException("limit cushion must be between 0 and " + MAX_LIMIT_CUSHION_BPS + " bps, got " + bps);
        }
        this.limitCushionBps = bps;
    }

    public double limitCushionBps() {
        return limitCushionBps;
    }

    @Override
    public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        String venueSymbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        Touch touch = touch(venueSymbol, bid, ask);
        double cushion = limitCushionBps;
        double limit = limitPrice(order, touch.bid(), touch.ask(), cushion);
        String limitText = String.format(Locale.ROOT, "%.2f", limit) + " (" + priceSource(order, touch)
                + (cushion > 0.0 ? String.format(Locale.ROOT, ", %.0f bps cushion", cushion) : "") + ")";
        String clientOrderId = "blackthorn-" + clock.millis() + "-" + sequence.incrementAndGet();

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

    @Override
    public ExecutionResult transmitMultiLeg(List<Order> legs, List<String> symbols, List<Double> bids, List<Double> asks, double netLimitPrice) {
        return transmitMultiLeg(legs, symbols, bids, asks, netLimitPrice, null);
    }

    @Override
    public ExecutionResult transmitMultiLeg(List<Order> legs, List<String> symbols, List<Double> bids, List<Double> asks, double netLimitPrice,
                                            List<Boolean> closing) {
        if (legs == null || symbols == null || legs.isEmpty() || legs.size() != symbols.size()) {
            return remember(new ExecutionResult("UNKNOWN", 0, 0.0, false, "Invalid multi-leg parameters"));
        }
        boolean allBuy = legs.stream().allMatch(Order::isBuy);
        boolean allSell = legs.stream().noneMatch(Order::isBuy);
        if (!allBuy && !allSell) {
            return remember(new ExecutionResult(symbols.get(0), 0, 0.0, false,
                    "multi-leg orders that mix buys and sells are not enabled: the net price would need a sign no order here has been shown to use"));
        }
        boolean everyLegCloses = closing != null && closing.size() == legs.size() && closing.stream().allMatch(Boolean.TRUE::equals);
        if (allSell && !everyLegCloses) {
            // A sell that opens a position writes an option, which this account is not eligible for (Alpaca answers 403, uncovered
            // option contracts); only a sell that closes a long already held is sent.
            return remember(new ExecutionResult(symbols.get(0), 0, 0.0, false,
                    "multi-leg sells that would open a position (a net credit from uncovered short options) are not enabled: only a close of legs held is"));
        }
        List<AlpacaPaperClient.OptionLeg> clientLegs = new java.util.ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            boolean legCloses = closing != null && i < closing.size() && Boolean.TRUE.equals(closing.get(i));
            clientLegs.add(new AlpacaPaperClient.OptionLeg(symbols.get(i), 1, legs.get(i).isBuy(), legCloses));
        }
        int quantity = legs.get(0).quantity();
        double cushion = limitCushionBps;
        if (allBuy) {
            if (cushion > 0.0) {
                // a debit: raise the most that will be paid
                netLimitPrice = Math.ceil(netLimitPrice * (1.0 + cushion / 10_000.0) * 100.0 - 1e-9) / 100.0;
            }
        } else {
            // proceeds: Alpaca prices a credit as a negative net, and the cushion lowers what is asked for
            double proceeds = cushion > 0.0
                    ? Math.floor(netLimitPrice * (1.0 - cushion / 10_000.0) * 100.0 + 1e-9) / 100.0
                    : netLimitPrice;
            netLimitPrice = -Math.abs(proceeds);
        }
        String clientOrderId = "blackthorn-mleg-" + clock.millis() + "-" + sequence.incrementAndGet();

        AlpacaPaperClient.OrderState state;
        try {
            state = client.submitMultiLegOrder(clientLegs, quantity, netLimitPrice, clientOrderId);
        } catch (AlpacaPaperClient.AlpacaException rejected) {
            return remember(new ExecutionResult(symbols.get(0), 0, 0.0, false, "Alpaca rejected multi-leg order: " + rejected.getMessage()));
        } catch (RuntimeException unreachable) {
            return remember(new ExecutionResult(symbols.get(0), 0, 0.0, false, "Alpaca unreachable: " + rootMessage(unreachable)));
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
            return remember(cancelAndReport(state, symbols.get(0), legs.get(0), "interrupted while waiting for multi-leg fill"));
        } catch (RuntimeException lost) {
            return remember(cancelAndReport(state, symbols.get(0), legs.get(0), "lost contact while waiting for multi-leg fill (" + rootMessage(lost) + ")"));
        }
        if (state.isWorking()) {
            return remember(cancelAndReport(state, symbols.get(0), legs.get(0), "no multi-leg fill within " + fillWait.toSeconds() + " s at limit " + netLimitPrice));
        }
        ExecutionResult filled = report(state, symbols.get(0), legs.get(0), null);
        if (filled.executed()) {
            // proceeds are reported as a positive amount, whichever sign the venue gives a credit
            filled = new ExecutionResult(filled.symbol(), filled.filledQuantity(), Math.abs(filled.executionPrice()), true, filled.message(),
                    legFillPrices(state, symbols));
        }
        return remember(filled);
    }

    /**
     * The price each leg filled at, in the order the legs were sent and matched by contract symbol (the venue does not promise
     * an order); empty unless every sent leg has a filled price, so the result is never half-known.
     */
    static List<Double> legFillPrices(AlpacaPaperClient.OrderState state, List<String> symbols) {
        List<Double> prices = new java.util.ArrayList<>();
        for (String symbol : symbols) {
            double price = Double.NaN;
            for (AlpacaPaperClient.LegFill leg : state.legs()) {
                if (symbol.equalsIgnoreCase(leg.symbol()) && leg.filledQty() > 0.0 && leg.filledAvgPrice() > 0.0) {
                    price = leg.filledAvgPrice();
                    break;
                }
            }
            if (!Double.isFinite(price)) {
                return List.of();
            }
            prices.add(price);
        }
        return prices;
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
        // a multi-leg credit may be reported with a negative average price: a fill is a fill whichever sign
        if (filled > 0 && Double.isFinite(state.filledAvgPrice()) && Math.abs(state.filledAvgPrice()) > 0.0) {
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
        return limitPrice(order, bid, ask, 0.0);
    }

    /** The limit for an order: the touch on its side, moved {@code cushionBps} past it (up for a buy, down for a sell), in cents. */
    static double limitPrice(Order order, double bid, double ask, double cushionBps) {
        double reference;
        if (order.isBuy()) {
            reference = Double.isFinite(ask) && ask > 0.0 ? ask : order.price();
        } else {
            reference = Double.isFinite(bid) && bid > 0.0 ? bid : order.price();
        }
        if (cushionBps > 0.0) {
            return order.isBuy()
                    ? Math.ceil(reference * (1.0 + cushionBps / 10_000.0) * 100.0 - 1e-9) / 100.0
                    : Math.floor(reference * (1.0 - cushionBps / 10_000.0) * 100.0 + 1e-9) / 100.0;
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
