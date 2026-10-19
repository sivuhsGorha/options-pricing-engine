package com.sbk.optionspricer.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal but realistic order lifecycle manager that enforces risk checks before
 * submission and tracks the order state across the execution pipeline.
 */
public class OrderManager {

    public record OrderDecision(boolean accepted, OrderStatus status, String message, long orderId) {}

    public record ExecutionAuditRecord(
            long orderId, boolean accepted, com.sbk.optionspricer.market.MarketDataStatus sourceStatus,
            String rejectionReason, double fillPrice, int quantity, java.time.Instant timestamp
    ) {}

    /**
     * Which market data may back an order. STALE and UNAVAILABLE data is never tradable;
     * SIMULATED data is tradable only when explicitly allowed (paper trading without a live feed).
     */
    public record MarketDataPolicy(java.util.Set<com.sbk.optionspricer.market.MarketDataStatus> tradable, long maxQuoteAgeMs) {
        public MarketDataPolicy {
            tradable = java.util.Set.copyOf(tradable);
            if (tradable.contains(com.sbk.optionspricer.market.MarketDataStatus.STALE)
                    || tradable.contains(com.sbk.optionspricer.market.MarketDataStatus.UNAVAILABLE)) {
                throw new IllegalArgumentException("STALE and UNAVAILABLE market data can never be tradable");
            }
            if (maxQuoteAgeMs <= 0L) {
                throw new IllegalArgumentException("maxQuoteAgeMs must be positive");
            }
        }

        /** Oldest quote an order may be based on; matches the adapter's DELAYED freshness window (a LIVE quote is STALE far sooner). */
        public static final long DEFAULT_MAX_QUOTE_AGE_MS = 120_000L;

        public static MarketDataPolicy strict() {
            return new MarketDataPolicy(java.util.EnumSet.of(
                    com.sbk.optionspricer.market.MarketDataStatus.LIVE,
                    com.sbk.optionspricer.market.MarketDataStatus.DELAYED), DEFAULT_MAX_QUOTE_AGE_MS);
        }

        public static MarketDataPolicy allowSimulated() {
            return new MarketDataPolicy(java.util.EnumSet.of(
                    com.sbk.optionspricer.market.MarketDataStatus.LIVE,
                    com.sbk.optionspricer.market.MarketDataStatus.DELAYED,
                    com.sbk.optionspricer.market.MarketDataStatus.SIMULATED), DEFAULT_MAX_QUOTE_AGE_MS);
        }
    }

    private final PreTradeRiskFilter riskFilter;
    private final ExchangeTransport transport;
    private final MarketDataPolicy marketDataPolicy;
    private final TradingHalt tradingHalt;
    private final PortfolioRiskAdmission portfolioAdmission;
    /** Units of the underlying per contract; scales every notional and exposure the same way. */
    private final int contractMultiplier;
    private final PositionTracker positionTracker;
    private final AtomicLong sequence = new AtomicLong(1L);
    /** Orders still working at the venue (ACCEPTED or PARTIALLY_FILLED). Filled/cancelled/rejected orders are not kept here. */
    private final Map<Long, Order> openOrders = new LinkedHashMap<>();
    private static final int MAX_TRACKED_ORDERS = 10_000;
    /** Latest status per order id, bounded so a long-running process cannot grow it without limit. */
    private final Map<Long, OrderStatus> orderStatuses = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, OrderStatus> eldest) {
            return size() > MAX_TRACKED_ORDERS;
        }
    };
    private final java.util.Deque<ExecutionAuditRecord> auditQueue = new java.util.ArrayDeque<>();
    private volatile Runnable fillListener = () -> { };

    /**
     * Runs after every booked fill, on the submitting thread: the application passes the valuation service's
     * revalue, so a new option position is priced at once instead of at the next scheduled valuation.
     */
    public void setFillListener(Runnable listener) {
        this.fillListener = listener == null ? () -> { } : listener;
    }

    /** Uses {@link MarketDataPolicy#strict()}: only fresh LIVE/DELAYED data is tradable. */
    public OrderManager(PreTradeRiskFilter riskFilter, ExchangeTransport transport, PositionTracker positionTracker) {
        this(riskFilter, transport, positionTracker, MarketDataPolicy.strict());
    }

    public OrderManager(PreTradeRiskFilter riskFilter, ExchangeTransport transport, PositionTracker positionTracker,
                        MarketDataPolicy marketDataPolicy) {
        this(riskFilter, transport, positionTracker, marketDataPolicy, new TradingHalt());
    }

    public OrderManager(PreTradeRiskFilter riskFilter, ExchangeTransport transport, PositionTracker positionTracker,
                        MarketDataPolicy marketDataPolicy, TradingHalt tradingHalt) {
        this(riskFilter, transport, positionTracker, marketDataPolicy, tradingHalt, null);
    }

    /** {@code portfolioAdmission} may be null to skip the portfolio Greek/notional admission check. Multiplier 1 (shares). */
    public OrderManager(PreTradeRiskFilter riskFilter, ExchangeTransport transport, PositionTracker positionTracker,
                        MarketDataPolicy marketDataPolicy, TradingHalt tradingHalt, PortfolioRiskAdmission portfolioAdmission) {
        this(riskFilter, transport, positionTracker, marketDataPolicy, tradingHalt, portfolioAdmission, 1);
    }

    /**
     * @param contractMultiplier units of the underlying per contract (1 for shares, 100 for standard equity options);
     *                           it scales the order notional checked by the risk filter, the concentration exposure,
     *                           the booked position and the portfolio admission check identically
     */
    public OrderManager(PreTradeRiskFilter riskFilter, ExchangeTransport transport, PositionTracker positionTracker,
                        MarketDataPolicy marketDataPolicy, TradingHalt tradingHalt, PortfolioRiskAdmission portfolioAdmission,
                        int contractMultiplier) {
        if (contractMultiplier < 1) {
            throw new IllegalArgumentException("contractMultiplier must be at least 1");
        }
        this.contractMultiplier = contractMultiplier;
        this.portfolioAdmission = portfolioAdmission;
        if (marketDataPolicy == null) {
            throw new IllegalArgumentException("marketDataPolicy must not be null");
        }
        if (tradingHalt == null) {
            throw new IllegalArgumentException("tradingHalt must not be null");
        }
        this.marketDataPolicy = marketDataPolicy;
        this.tradingHalt = tradingHalt;
        if (riskFilter == null) {
            throw new IllegalArgumentException("riskFilter must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.riskFilter = riskFilter;
        this.transport = transport;
        this.positionTracker = positionTracker;
    }

    /**
     * Admission, risk, transmission and fill booking run as one atomic step under this manager's
     * lock, so two concurrent orders can never both pass a limit check against the same book.
     */
    /**
     * True when the order only reduces the position already held in this instrument: a buy against a short, or a sell against
     * a long, for no more than is held. An order that opens a position, adds to it, or would carry it through zero is an entry.
     */
    private boolean reducesPosition(Order order, String symbol) {
        if (positionTracker == null || symbol == null) {
            return false;
        }
        int net = positionTracker.getNetQuantity(symbol);
        return order.isBuy() ? (net < 0 && order.quantity() <= -net) : (net > 0 && order.quantity() <= net);
    }

    public synchronized OrderDecision submit(Order order, com.sbk.optionspricer.market.MarketSnapshot snapshot) {
        if (order == null || snapshot == null) {
            return new OrderDecision(false, OrderStatus.REJECTED, "order and snapshot must not be null", -1L);
        }

        long orderId = sequence.getAndIncrement();
        com.sbk.optionspricer.market.MarketDataStatus mds = snapshot.status();

        java.util.Optional<TradingHalt.Reason> halted = tradingHalt.reason();
        if (halted.isPresent()) {
            String reason = "trading halted: " + halted.get().message();
            return reject(orderId, mds, reason, reason);
        }

        if (!marketDataPolicy.tradable().contains(mds)) {
            String reason = "market data not tradable: " + mds;
            return reject(orderId, mds, reason, reason);
        }
        if (snapshot.derivedQuoteAgeMs() > marketDataPolicy.maxQuoteAgeMs()) {
            String reason = "market data too old: " + snapshot.derivedQuoteAgeMs() + "ms";
            return reject(orderId, mds, reason, reason);
        }

        // An option is booked under its contract symbol with the contract's own multiplier; its notional and
        // concentration count against the underlying. A stock uses the configured multiplier.
        int multiplier = snapshot.multiplierOr(contractMultiplier);
        String underlying = snapshot.underlying();

        if (portfolioAdmission != null && positionTracker != null) {
            int signedRequest = order.isBuy() ? order.quantity() : -order.quantity();
            if (!portfolioAdmission.canAdmitOrder(snapshot.symbol(), signedRequest, order.price(), positionTracker, multiplier)) {
                return reject(orderId, mds, "failed portfolio risk admission", "order failed portfolio risk admission");
            }
        }

        if (!riskFilter.checkRisk(order, underlying, snapshot.bid(), snapshot.ask(), snapshot.volume(), multiplier,
                reducesPosition(order, snapshot.symbol()))) {
            return reject(orderId, mds, "failed pre-trade risk validation", "order failed pre-trade risk validation");
        }

        ExecutionResult result = transport.transmit(order, snapshot.symbol(), snapshot.bid(), snapshot.ask());
        if (!result.executed()) {
            String why = result.message() == null || result.message().isBlank() ? "" : ": " + result.message();
            return reject(orderId, mds, result.message(), "execution transport rejected the order" + why);
        }

        int filled = result.filledQuantity();
        if (filled <= 0 || filled > order.quantity()) {
            // The venue says it executed, but the quantity cannot be reconciled with what we sent.
            // Do not book a number we cannot trust, and stop trading until someone reconciles.
            tradingHalt.halt("transport reported fill quantity " + filled + " for order " + orderId
                    + " (requested " + order.quantity() + "); reconcile with the venue");
            return reject(orderId, mds, "invalid fill quantity from transport: " + filled,
                    "transport reported an invalid fill quantity; trading halted");
        }

        OrderStatus status = filled == order.quantity() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        try {
            if (positionTracker != null) {
                int signedQuantity = order.isBuy() ? filled : -filled;
                positionTracker.applyFill(new PositionTracker.ExecutionFill(result.symbol(), signedQuantity, multiplier, result.executionPrice()));
            }
            double filledNotional = (double) filled * result.executionPrice() * multiplier;
            riskFilter.recordFill(underlying, order.isBuy() ? filledNotional : -filledNotional);
        } catch (RuntimeException e) {
            // The order executed at the venue but our books may not reflect it. Never throw to the
            // caller as if nothing happened, and never keep trading on an unknown position.
            tradingHalt.halt("fill booking failed for order " + orderId + ": " + e.getMessage());
            recordAudit(orderId, true, mds, "EXECUTED_BOOKING_FAILED: " + e.getMessage(), result.executionPrice(), filled);
            orderStatuses.put(orderId, status);
            return new OrderDecision(true, status, "order executed but booking failed; trading halted", orderId);
        }

        recordAudit(orderId, true, mds, "EXECUTED", result.executionPrice(), filled);
        orderStatuses.put(orderId, status);
        if (status == OrderStatus.PARTIALLY_FILLED) {
            openOrders.put(orderId, order);
        }
        try {
            fillListener.run();
        } catch (RuntimeException e) {
            // Valuing the book is not booking it: the fill is in, and the next scheduled valuation retries.
            System.err.println("[ORDER MANAGER] post-fill listener failed: " + e.getMessage());
        }
        return new OrderDecision(true, status, status == OrderStatus.FILLED ? "order filled" : "order partially filled", orderId);
    }

    /**
     * Submits a multi-leg complex order (e.g. straddle) atomically. All legs are admitted and executed together,
     * eliminating legging risk and the need for flatten fallbacks.
     */
    public synchronized OrderDecision submitMultiLeg(java.util.List<Order> legOrders,
                                                     java.util.List<com.sbk.optionspricer.market.MarketSnapshot> snapshots,
                                                     double netLimitPrice) {
        if (legOrders == null || snapshots == null || legOrders.isEmpty() || legOrders.size() != snapshots.size()) {
            return new OrderDecision(false, OrderStatus.REJECTED, "legOrders and snapshots must not be null and match in size", -1L);
        }

        long orderId = sequence.getAndIncrement();
        java.util.Optional<TradingHalt.Reason> halted = tradingHalt.reason();
        if (halted.isPresent()) {
            String reason = "trading halted: " + halted.get().message();
            return reject(orderId, snapshots.get(0).status(), reason, reason);
        }

        java.util.List<String> symbols = new java.util.ArrayList<>();
        java.util.List<Double> bids = new java.util.ArrayList<>();
        java.util.List<Double> asks = new java.util.ArrayList<>();

        for (int i = 0; i < legOrders.size(); i++) {
            Order order = legOrders.get(i);
            com.sbk.optionspricer.market.MarketSnapshot snapshot = snapshots.get(i);
            com.sbk.optionspricer.market.MarketDataStatus mds = snapshot.status();

            if (!marketDataPolicy.tradable().contains(mds)) {
                String reason = "market data not tradable for leg " + snapshot.symbol() + ": " + mds;
                return reject(orderId, mds, reason, reason);
            }
            if (snapshot.derivedQuoteAgeMs() > marketDataPolicy.maxQuoteAgeMs()) {
                String reason = "market data too old for leg " + snapshot.symbol() + ": " + snapshot.derivedQuoteAgeMs() + "ms";
                return reject(orderId, mds, reason, reason);
            }

            int multiplier = snapshot.multiplierOr(contractMultiplier);
            String underlying = snapshot.underlying();

            if (portfolioAdmission != null && positionTracker != null) {
                int signedRequest = order.isBuy() ? order.quantity() : -order.quantity();
                if (!portfolioAdmission.canAdmitOrder(snapshot.symbol(), signedRequest, order.price(), positionTracker, multiplier)) {
                    return reject(orderId, mds, "failed portfolio risk admission on leg " + snapshot.symbol(), "order failed portfolio risk admission");
                }
            }

            if (!riskFilter.checkRisk(order, underlying, snapshot.bid(), snapshot.ask(), snapshot.volume(), multiplier,
                    reducesPosition(order, snapshot.symbol()))) {
                return reject(orderId, mds, "failed pre-trade risk validation on leg " + snapshot.symbol(), "order failed pre-trade risk validation");
            }

            symbols.add(snapshot.symbol());
            bids.add(snapshot.bid());
            asks.add(snapshot.ask());
        }

        ExecutionResult result = transport.transmitMultiLeg(legOrders, symbols, bids, asks, netLimitPrice);
        if (!result.executed()) {
            return reject(orderId, snapshots.get(0).status(), result.message(), "execution transport rejected multi-leg order: " + result.message());
        }

        int filled = result.filledQuantity();
        OrderStatus status = filled == legOrders.get(0).quantity() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;

        // The venue reports one net price for the whole order. Booking each leg at its own limit price would make the
        // book's cost differ from what the account was charged, so the net price is split in proportion to the legs'
        // limit prices. Only meaningful when every leg is on the same side (a pair of buys); otherwise limits stand.
        double legScale = 1.0;
        boolean sameSide = legOrders.stream().allMatch(o -> o.isBuy() == legOrders.get(0).isBuy());
        double limitSum = legOrders.stream().mapToDouble(Order::price).sum();
        if (sameSide && limitSum > 0.0 && Double.isFinite(result.executionPrice()) && result.executionPrice() > 0.0) {
            legScale = result.executionPrice() / limitSum;
        }

        try {
            if (positionTracker != null) {
                for (int i = 0; i < legOrders.size(); i++) {
                    Order order = legOrders.get(i);
                    com.sbk.optionspricer.market.MarketSnapshot snapshot = snapshots.get(i);
                    int mult = snapshot.multiplierOr(contractMultiplier);
                    int signedQuantity = order.isBuy() ? filled : -filled;
                    double bookedPrice = order.price() * legScale;
                    positionTracker.applyFill(new PositionTracker.ExecutionFill(snapshot.symbol(), signedQuantity, mult, bookedPrice));
                    double notional = (double) filled * bookedPrice * mult;
                    riskFilter.recordFill(snapshot.underlying(), order.isBuy() ? notional : -notional);
                }
            }
        } catch (RuntimeException e) {
            tradingHalt.halt("multi-leg fill booking failed for order " + orderId + ": " + e.getMessage());
            recordAudit(orderId, true, snapshots.get(0).status(), "EXECUTED_BOOKING_FAILED: " + e.getMessage(), result.executionPrice(), filled);
            orderStatuses.put(orderId, status);
            return new OrderDecision(true, status, "multi-leg executed but booking failed; trading halted", orderId);
        }

        recordAudit(orderId, true, snapshots.get(0).status(), "EXECUTED_MULTI_LEG", result.executionPrice(), filled);
        orderStatuses.put(orderId, status);

        try {
            fillListener.run();
        } catch (RuntimeException e) {
            System.err.println("[ORDER MANAGER] post-fill listener failed: " + e.getMessage());
        }
        return new OrderDecision(true, status, "multi-leg order filled", orderId);
    }

    private OrderDecision reject(long orderId, com.sbk.optionspricer.market.MarketDataStatus mds, String auditReason, String message) {
        recordAudit(orderId, false, mds, auditReason, 0.0, 0);
        orderStatuses.put(orderId, OrderStatus.REJECTED);
        return new OrderDecision(false, OrderStatus.REJECTED, message, orderId);
    }

    /** The audit trail has its own lock: a reader must not wait for an order that is waiting on the venue. */
    private final Object auditLock = new Object();

    private void recordAudit(long orderId, boolean accepted, com.sbk.optionspricer.market.MarketDataStatus sourceStatus,
                             String rejectionReason, double fillPrice, int quantity) {
        synchronized (auditLock) {
            auditQueue.addLast(new ExecutionAuditRecord(orderId, accepted, sourceStatus, rejectionReason, fillPrice, quantity, java.time.Instant.now()));
            if (auditQueue.size() > 1000) {
                auditQueue.removeFirst();
            }
        }
    }

    public java.util.List<ExecutionAuditRecord> getAuditTrail() {
        synchronized (auditLock) {
            return new java.util.ArrayList<>(auditQueue);
        }
    }

    /**
     * Cancels the unfilled remainder of a working order. Quantity already executed stays booked.
     * Local state only: no transport leaves an order working at the venue (the Alpaca transport cancels the
     * remainder itself before it returns), so there is nothing to cancel there.
     */
    public synchronized void cancel(long orderId) {
        OrderStatus status = orderStatuses.get(orderId);
        if (status == null) {
            throw new IllegalArgumentException("order not found: " + orderId);
        }
        if (openOrders.remove(orderId) == null) {
            throw new IllegalStateException("order " + orderId + " is not working (status " + status + ")");
        }
        orderStatuses.put(orderId, OrderStatus.CANCELLED);
    }

    public synchronized java.util.Optional<OrderStatus> getOrderStatus(long orderId) {
        return java.util.Optional.ofNullable(orderStatuses.get(orderId));
    }

    public TradingHalt getTradingHalt() {
        return tradingHalt;
    }

    public synchronized Map<Long, Order> getOpenOrders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(openOrders));
    }
}
