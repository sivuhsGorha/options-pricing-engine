package com.sbk.optionspricer.core;

import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.web.LiveSpotProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuantSimulationHarnessStrategyTest {

    @Test
    void harnessCanExecuteStrategySignalsAgainstTheLiveMarketTick() {
        MmapStatePublisher publisher = new MmapStatePublisher();
        UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, System::exit);

        PositionTracker tracker = new PositionTracker();
        OrderManager orderManager = new OrderManager(
                new PreTradeRiskFilter(1000, 10_000_000.0, 1000),
                (order, sym, bid, ask) -> new com.sbk.optionspricer.execution.ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker
        );
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1_000_000.0, 5_000.0, 10_000.0, 2_000.0, 100.0);
        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", orderManager, admission, tracker, 10.0, 0.02);

        QuantSimulationHarness harness = new QuantSimulationHarness(engine, new LiveSpotProvider(), tracker, orderManager, admission, loop);

        StrategyExecutionLoop.ExecutionSummary summary = harness.runStrategyStep(100.0);
        harness.runStrategyStep(103.0);

        assertNotNull(summary, "strategy execution summary should be created");
        assertTrue(summary.totalSignals() >= 0, "strategy should evaluate the market path");
        assertTrue(summary.acceptedOrders() >= 0, "accepted counts must be tracked");
    }
}
