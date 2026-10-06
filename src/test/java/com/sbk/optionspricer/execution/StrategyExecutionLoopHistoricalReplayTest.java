package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.data.OptionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrategyExecutionLoopHistoricalReplayTest {

    @Test
    void replaysHistoricalSnapshotsThroughTheStrategyExecutionLoop() {
        PositionTracker tracker = new PositionTracker();
        OrderManager orderManager = new OrderManager(
                new PreTradeRiskFilter(1000, 10_000_000.0, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                new PositionTracker()
        );
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1_000_000.0, 5_000.0, 10_000.0, 2_000.0, 100.0);

        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", orderManager, admission, tracker, 10.0, 0.004);

        List<OptionSnapshot> snapshots = List.of(
                new OptionSnapshot(Instant.parse("2024-01-02T09:30:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 510.0, 509.5, 510.5, 0.22, 1000, 2000),
                new OptionSnapshot(Instant.parse("2024-01-02T09:31:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.5, 511.8, 513.2, 0.25, 1200, 2100),
                new OptionSnapshot(Instant.parse("2024-01-02T09:32:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 515.0, 514.4, 515.6, 0.28, 1300, 2200),
                new OptionSnapshot(Instant.parse("2024-01-02T09:33:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.0, 511.7, 512.3, 0.30, 1250, 2150)
        );

        StrategyExecutionLoop.ExecutionSummary summary = loop.runHistoricalReplay(snapshots);

        assertTrue(summary.totalSignals() >= 1, "the historical replay should trigger at least one valid signal");
        assertTrue(summary.acceptedOrders() >= 1, "at least one strategy order should clear the execution gate");
        assertTrue(summary.netQuantity() >= -1000, "net quantity should remain bounded by the strategy path");
    }
}
