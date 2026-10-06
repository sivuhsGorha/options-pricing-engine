package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.data.OptionSnapshot;
import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal portfolio backtest orchestrator. It combines a strategy path with a replay
 * engine to produce the high-level summary expected from a full trading simulation.
 */
public class PortfolioBacktestOrchestrator {

    public record PortfolioBacktestResult(
            int snapshotsProcessed,
            int totalSignals,
            int acceptedOrders,
            int rejectedOrders,
            int netQuantity,
            double totalPnL,
            double averageSlippage,
            double maxDrawdown
    ) {}

    public static PortfolioBacktestResult run(List<OptionSnapshot> snapshots) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (snapshots.isEmpty()) {
            return new PortfolioBacktestResult(0, 0, 0, 0, 0, 0.0, 0.0, 0.0);
        }

        List<OptionSnapshot> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator.comparing(OptionSnapshot::timestamp));

        PositionTracker tracker = PositionTracker.inMemory();
        OrderManager orderManager = new OrderManager(
                new PreTradeRiskFilter(1000, 10_000_000.0, 1000),
                (order, sym, bid, ask) -> new com.sbk.optionspricer.execution.ExecutionResult(sym, order.quantity(), order.price(), true, "EXECUTED"),
                tracker,
                OrderManager.MarketDataPolicy.allowSimulated()
        );
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1_000_000.0, 5_000.0, 10_000.0, 2_000.0, 100.0);
        StrategyExecutionLoop strategy = new StrategyExecutionLoop("SPY", orderManager, admission, tracker, 10.0, 0.004);

        StrategyExecutionLoop.ExecutionSummary strategySummary = strategy.runHistoricalReplay(ordered);
        HistoricalReplayBacktester.ReplayResult replay = HistoricalReplayBacktester.runReplay(ordered, 10, 0.45);

        return new PortfolioBacktestResult(
                ordered.size(),
                strategySummary.totalSignals(),
                strategySummary.acceptedOrders(),
                strategySummary.rejectedOrders(),
                strategySummary.netQuantity(),
                replay.totalPnL(),
                replay.averageSlippage(),
                replay.maxDrawdown()
        );
    }
}
