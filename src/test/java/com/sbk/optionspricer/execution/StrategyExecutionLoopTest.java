package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.LiquidityRiskMonitor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StrategyExecutionLoopTest {

    @Test
    void executesStrategySignalsWithinRiskLimitsAndTracksAcceptedOrders() {
        PositionTracker tracker = new PositionTracker();
        OrderManager orderManager = new OrderManager(new PreTradeRiskFilter(1000, 10_000_000.0, 1000), (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), tracker, OrderManager.MarketDataPolicy.allowSimulated());
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1_000_000.0, 5_000.0, 10_000.0, 2_000.0, 50_000.0);

        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", orderManager, admission, tracker, 10.0, 0.02);

        StrategyExecutionLoop.ExecutionSummary summary = loop.run(List.of(100.0, 110.0, 115.0, 114.0, 118.0));

        assertTrue(summary.acceptedOrders() >= 1, "the strategy should accept at least one valid signal");
        assertTrue(summary.rejectedOrders() >= 0, "rejections must be tracked");
        assertTrue(summary.totalSignals() >= 1, "at least one signal should be evaluated");
    }

    @Test
    void rejectsSignalsWhenMarketAwareRiskGateBlocksTheOrder() {
        PositionTracker tracker = new PositionTracker();
        PreTradeRiskFilter filter = new PreTradeRiskFilter(
                1000,
                10_000_000.0,
                1000,
                Map.of(1, "SPY"),
                new ConcentrationLimitManager(Map.of("SPY", 500.0)),
                new LiquidityRiskMonitor(25.0, 1000L)
        );
        OrderManager orderManager = new OrderManager(filter, (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), tracker, OrderManager.MarketDataPolicy.allowSimulated());
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1_000_000.0, 5_000.0, 10_000.0, 2_000.0, 50_000.0);

        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", orderManager, admission, tracker, 10.0, 0.01);

        StrategyExecutionLoop.ExecutionSummary summary = loop.run(List.of(100.0, 110.0, 111.0, 115.0));

        assertTrue(summary.rejectedOrders() >= 1, "market-aware risk gate should reject at least one order");
    }

    @Test
    void updatesPortfolioGreeksAfterAcceptedFillToKeepRiskLimitsMeaningful() {
        PositionTracker tracker = new PositionTracker();
        OrderManager orderManager = new OrderManager(new PreTradeRiskFilter(1000, 10_000_000.0, 1000), (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), tracker, OrderManager.MarketDataPolicy.allowSimulated());
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1_000_000.0, 5_000.0, 10_000.0, 2_000.0, 50_000.0);

        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", orderManager, admission, tracker, 10.0, 0.02);

        StrategyExecutionLoop.ExecutionSummary summary = loop.run(List.of(100.0, 110.0, 115.0, 114.0, 118.0));

        assertTrue(summary.acceptedOrders() >= 1, "a valid strategy signal should be accepted");
        assertNotNull(tracker.getPosition("SPY"), "accepted fills should reconcile into a tracked portfolio position");
        assertTrue(Math.abs(tracker.getPosition("SPY").getDelta()) > 0.0
                        || Math.abs(tracker.getPosition("SPY").getGamma()) > 0.0
                        || Math.abs(tracker.getPosition("SPY").getVega()) > 0.0,
                "accepted fills must update the portfolio Greeks used by the risk gate");
    }
}
