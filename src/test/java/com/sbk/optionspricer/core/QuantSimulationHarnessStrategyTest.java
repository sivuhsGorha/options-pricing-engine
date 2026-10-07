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

    /** No keys and no network: the provider reports no market data and never makes a request. */
    private static LiveSpotProvider offlineProvider() {
        return new LiveSpotProvider(null, null, null, null, (url, headers) -> { throw new java.io.IOException("no network in tests"); });
    }

    @Test
    void harnessCanExecuteStrategySignalsAgainstTheLiveMarketTick() {
        // Own state file: the default would be the running application's mmap file named in .env.
        System.setProperty("MMAP_STATE_FILE", "target/harness-strategy-test-state.dat");
        try {
            MmapStatePublisher publisher = new MmapStatePublisher();
            UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, code -> { });

            PositionTracker tracker = new PositionTracker(com.sbk.optionspricer.risk.FillRecorder.NONE);
            OrderManager orderManager = new OrderManager(
                    new PreTradeRiskFilter(1000, 10_000_000.0, 1000),
                    (order, sym, bid, ask) -> new com.sbk.optionspricer.execution.ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                    tracker,
                    OrderManager.MarketDataPolicy.allowSimulated() // the loop has no live snapshot source here
            );
            PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1_000_000.0, 5_000.0, 10_000.0, 2_000.0, 100.0);
            StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", orderManager, admission, tracker, 10.0, 0.02);

            QuantSimulationHarness harness = new QuantSimulationHarness(engine, offlineProvider(), tracker, orderManager, admission, loop);

            StrategyExecutionLoop.ExecutionSummary first = harness.runStrategyStep(100.0);
            StrategyExecutionLoop.ExecutionSummary second = harness.runStrategyStep(103.0);

            assertEquals(0, first.totalSignals(), "the first price only seeds the loop");
            assertEquals(1, second.totalSignals(), "a 3% move against a 2% trigger is one signal");
            assertEquals(1, second.acceptedOrders());
            assertEquals(10, tracker.getNetQuantity("SPY"), "the paper fill is booked");
            publisher.close();
        } finally {
            System.clearProperty("MMAP_STATE_FILE");
        }
    }
}
