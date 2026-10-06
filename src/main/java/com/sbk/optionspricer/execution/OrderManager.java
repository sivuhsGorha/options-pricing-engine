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

        public static MarketDataPolicy strict() {
            return new MarketDataPolicy(java.util.EnumSet.of(
                    com.sbk.optionspricer.market.MarketDataStatus.LIVE,
                    com.sbk.optionspricer.market.MarketDataStatus.DELAYED), 30_000L);
        }

        public static MarketDataPolicy allowSimulated() {
            return new MarketDataPolicy(java.util.EnumSet.of(
                    com.sbk.optionspricer.market.MarketDataStatus.LIVE,
                    com.sbk.optionspricer.market.MarketDataStatus.DELAYED,
                    com.sbk.optionspricer.market.MarketDataStatus.SIMULATED), 30_000L);
        }
    }

    private final PreTradeRiskFilter riskFilter;
    private final ExchangeTransport transport;
    private final MarketDataPolicy marketDataPolicy;
    private final TradingHalt tradingHalt;
    private final PortfolioRiskAdmission portfolioAdmission;
    private final PositionTracker positionTracker;
    private final AtomicLong sequence = new AtomicLong(1L);
    private final Map<Long, Order> openOrders = new LinkedHashMap<>();
    private final java.util.Deque<ExecutionAuditRecord> auditQueue = new java.util.ArrayDeque<>();

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

    /** {@code portfolioAdmission} may be null to skip the portfolio Greek/notional admission check. */
    public OrderManager(PreTradeRiskFilter riskFilter, ExchangeTransport transport, PositionTracker positionTracker,
                        MarketDataPolicy marketDataPolicy, TradingHalt tradingHalt, PortfolioRiskAdmission portfolioAdmission) {
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

    public OrderDecision submit(Order order, com.sbk.optionspricer.market.MarketSnapshot snapshot) {
        if (order == null || snapshot == null) {
            return new OrderDecision(false, OrderStatus.REJECTED, "order and snapshot must not be null", -1L);
        }
        
        long orderId = sequence.getAndIncrement();
        com.sbk.optionspricer.market.MarketDataStatus mds = snapshot.status();

        java.util.Optional<TradingHalt.Reason> halted = tradingHalt.reason();
        if (halted.isPresent()) {
            String reason = "trading halted: " + halted.get().message();
            recordAudit(orderId, false, mds, reason, 0.0, 0);
            return new OrderDecision(false, OrderStatus.REJECTED, reason, orderId);
        }

        if (!marketDataPolicy.tradable().contains(mds)) {
            String reason = "market data not tradable: " + mds;
            recordAudit(orderId, false, mds, reason, 0.0, 0);
            return new OrderDecision(false, OrderStatus.REJECTED, reason, orderId);
        }
        if (snapshot.derivedQuoteAgeMs() > marketDataPolicy.maxQuoteAgeMs()) {
            String reason = "market data too old: " + snapshot.derivedQuoteAgeMs() + "ms";
            recordAudit(orderId, false, mds, reason, 0.0, 0);
            return new OrderDecision(false, OrderStatus.REJECTED, reason, orderId);
        }

        if (portfolioAdmission != null && positionTracker != null) {
            int signedRequest = order.isBuy() ? order.quantity() : -order.quantity();
            if (!portfolioAdmission.canAdmitOrder(snapshot.symbol(), signedRequest, order.price(), positionTracker)) {
                recordAudit(orderId, false, mds, "failed portfolio risk admission", 0.0, 0);
                return new OrderDecision(false, OrderStatus.REJECTED, "order failed portfolio risk admission", orderId);
            }
        }

        if (!riskFilter.checkRisk(order, snapshot.symbol(), snapshot.bid(), snapshot.ask(), snapshot.volume())) {
            recordAudit(orderId, false, mds, "failed pre-trade risk validation", 0.0, 0);
            return new OrderDecision(false, OrderStatus.REJECTED, "order failed pre-trade risk validation", orderId);
        }

        ExecutionResult result = transport.transmit(order, snapshot.symbol(), snapshot.bid(), snapshot.ask());
        if (!result.executed()) {
            recordAudit(orderId, false, mds, result.message(), 0.0, 0);
            return new OrderDecision(false, OrderStatus.REJECTED, "execution transport rejected the order", orderId);
        }

        if (positionTracker != null) {
            int signedQuantity = order.isBuy() ? result.filledQuantity() : -result.filledQuantity();
            positionTracker.applyFill(new PositionTracker.ExecutionFill(result.symbol(), signedQuantity, 1, result.executionPrice()));
        }
        double filledNotional = (double) result.filledQuantity() * result.executionPrice();
        riskFilter.recordFill(result.symbol(), order.isBuy() ? filledNotional : -filledNotional);

        recordAudit(orderId, true, mds, "EXECUTED", result.executionPrice(), result.filledQuantity());
        openOrders.put(orderId, order);
        return new OrderDecision(true, OrderStatus.ACCEPTED, "order accepted and routed", orderId);
    }

    private synchronized void recordAudit(long orderId, boolean accepted, com.sbk.optionspricer.market.MarketDataStatus sourceStatus,
                             String rejectionReason, double fillPrice, int quantity) {
        auditQueue.addLast(new ExecutionAuditRecord(orderId, accepted, sourceStatus, rejectionReason, fillPrice, quantity, java.time.Instant.now()));
        if (auditQueue.size() > 1000) {
            auditQueue.removeFirst();
        }
    }

    public synchronized java.util.List<ExecutionAuditRecord> getAuditTrail() {
        return new java.util.ArrayList<>(auditQueue);
    }

    public void cancel(long orderId) {
        Order order = openOrders.remove(orderId);
        if (order == null) {
            throw new IllegalArgumentException("order not found: " + orderId);
        }
    }

    public TradingHalt getTradingHalt() {
        return tradingHalt;
    }

    public Map<Long, Order> getOpenOrders() {
        return Collections.unmodifiableMap(openOrders);
    }
}
