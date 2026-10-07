package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.data.OptionSnapshot;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.market.MarketSnapshotAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal signal-to-order loop that turns a price series into trade decisions and
 * records whether each decision was accepted by the portfolio risk gate and execution manager.
 */
public class StrategyExecutionLoop {

    public record ExecutionSummary(
            int totalSignals,
            int acceptedOrders,
            int rejectedOrders,
            int netQuantity
    ) {}

    private final String symbol;
    private final OrderManager orderManager;
    private final PortfolioRiskAdmission riskAdmission;
    private final PositionTracker positionTracker;
    private final double triggerPct;
    private final int baseQuantity;
    private final MarketSnapshotAdapter marketSnapshotAdapter;
    private double lastPrice = Double.NaN;

    public StrategyExecutionLoop(String symbol, OrderManager orderManager, PortfolioRiskAdmission riskAdmission,
                                PositionTracker positionTracker, double baseQuantity, double triggerPct) {
        this(symbol, orderManager, riskAdmission, positionTracker, baseQuantity, triggerPct, null);
    }

    public StrategyExecutionLoop(String symbol, OrderManager orderManager, PortfolioRiskAdmission riskAdmission,
                                PositionTracker positionTracker, double baseQuantity, double triggerPct,
                                MarketSnapshotAdapter marketSnapshotAdapter) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (orderManager == null || riskAdmission == null || positionTracker == null) {
            throw new IllegalArgumentException("execution dependencies must not be null");
        }
        if (!Double.isFinite(baseQuantity) || baseQuantity <= 0.0) {
            throw new IllegalArgumentException("baseQuantity must be positive");
        }
        if (!Double.isFinite(triggerPct) || triggerPct <= 0.0 || triggerPct >= 1.0) {
            throw new IllegalArgumentException("triggerPct must be between 0 and 1");
        }
        this.symbol = symbol;
        this.orderManager = orderManager;
        this.riskAdmission = riskAdmission;
        this.positionTracker = positionTracker;
        this.baseQuantity = (int) Math.round(baseQuantity);
        this.triggerPct = triggerPct;
        this.marketSnapshotAdapter = marketSnapshotAdapter;
    }

    public ExecutionSummary run(List<Double> prices) {
        return run(prices, null);
    }

    public ExecutionSummary run(List<Double> prices, List<MarketSnapshot> contexts) {
        if (marketSnapshotAdapter != null && (contexts == null || contexts.isEmpty())) {
            contexts = createContextsFromSnapshotAdapter(prices);
        }
        if (prices == null || prices.isEmpty()) {
            return new ExecutionSummary(0, 0, 0, 0);
        }

        double previous = prices.get(0);
        int accepted = 0;
        int rejected = 0;
        int totalSignals = 0;
        int netQty = 0;

        for (int i = 1; i < prices.size(); i++) {
            double current = prices.get(i);
            MarketSnapshot context = contexts != null && i < contexts.size() ? contexts.get(i) : null;
            ExecutionSummary step = evaluate(previous, current, context);
            totalSignals += step.totalSignals();
            accepted += step.acceptedOrders();
            rejected += step.rejectedOrders();
            netQty += step.netQuantity();
            previous = current;
        }

        return new ExecutionSummary(totalSignals, accepted, rejected, netQty);
    }

    /**
     * Live, stateful entry point: feed each new price once. The move is measured against the
     * previous price this loop saw, so a single jump yields a single signal however many ticks
     * the price then stays at the new level.
     *
     * @param context market snapshot backing the order; if null it is taken from the snapshot
     *                adapter when one is configured
     */
    public synchronized ExecutionSummary onPrice(double price, MarketSnapshot context) {
        if (!Double.isFinite(price) || price <= 0.0) {
            return new ExecutionSummary(0, 0, 0, 0);
        }
        double previous = lastPrice;
        lastPrice = price;
        if (Double.isNaN(previous)) {
            return new ExecutionSummary(0, 0, 0, 0);
        }
        return evaluate(previous, price, context);
    }

    /** Evaluates one price step: returns a zero summary below the trigger, otherwise one signal. */
    private ExecutionSummary evaluate(double previous, double current, MarketSnapshot context) {
        double relativeMove = Math.abs(current - previous) / Math.max(Math.abs(previous), 1.0);
        if (relativeMove < triggerPct) {
            return new ExecutionSummary(0, 0, 0, 0);
        }
        int direction = current > previous ? 1 : -1;
        int qty = direction * baseQuantity;
        if (context == null) {
            context = marketSnapshotAdapter != null
                    ? marketSnapshotAdapter.getSnapshot(symbol)
                    : new MarketSnapshot(symbol, current * 0.995, current * 1.005, current, MarketSnapshot.VOLUME_UNKNOWN, Instant.now(), Instant.now(), 0L,
                            "SIMULATED", com.sbk.optionspricer.market.MarketDataStatus.SIMULATED);
        }

        if (!riskAdmission.canAdmitOrder(symbol, qty, current, positionTracker)) {
            return new ExecutionSummary(1, 0, 1, 0);
        }
        OrderManager.OrderDecision decision = orderManager.submit(new Order(1, direction > 0, Math.abs(qty), current), context);
        return decision.accepted()
                ? new ExecutionSummary(1, 1, 0, qty)
                : new ExecutionSummary(1, 0, 1, 0);
    }

    private List<MarketSnapshot> createContextsFromSnapshotAdapter(List<Double> prices) {
        List<MarketSnapshot> contexts = new ArrayList<>(prices.size());
        for (int i = 0; i < prices.size(); i++) {
            MarketSnapshot snapshot = marketSnapshotAdapter.getSnapshot(symbol);
            contexts.add(snapshot);
        }
        return contexts;
    }

    public ExecutionSummary runHistoricalReplay(List<OptionSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return new ExecutionSummary(0, 0, 0, 0);
        }

        List<OptionSnapshot> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator.comparing(OptionSnapshot::timestamp));

        List<Double> prices = new ArrayList<>(ordered.size());
        for (OptionSnapshot snapshot : ordered) {
            prices.add(snapshot.midPrice());
        }
        return run(prices);
    }
}
