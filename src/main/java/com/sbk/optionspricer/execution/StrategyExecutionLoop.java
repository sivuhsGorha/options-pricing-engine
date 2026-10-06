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
            double relativeMove = Math.abs(current - previous) / Math.max(Math.abs(previous), 1.0);
            if (relativeMove >= triggerPct) {
                totalSignals++;
                int direction = current > previous ? 1 : -1;
                int qty = direction * baseQuantity;
                MarketSnapshot context = null;
                if (contexts != null && i < contexts.size() && contexts.get(i) != null) {
                    context = contexts.get(i);
                }
                if (context == null) {
                    context = new MarketSnapshot(symbol, current * 0.995, current * 1.005, current, 2000L, Instant.now(), Instant.now(), 0L, "SIMULATED", com.sbk.optionspricer.market.MarketDataStatus.SIMULATED);
                }

                boolean admitted = riskAdmission.canAdmitOrder(symbol, qty, current, positionTracker);
                if (admitted) {
                    OrderManager.OrderDecision decision = orderManager.submit(
                            new Order(1, direction > 0, Math.abs(qty), current),
                            context
                    );
                    if (decision.accepted()) {
                        accepted++;
                        netQty += qty;
                    } else {
                        rejected++;
                    }
                } else {
                    rejected++;
                }
            }
            previous = current;
        }

        return new ExecutionSummary(totalSignals, accepted, rejected, netQty);
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
