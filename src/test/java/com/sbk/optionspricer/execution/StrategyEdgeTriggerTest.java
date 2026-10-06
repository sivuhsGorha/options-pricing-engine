package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** A price move is one event: it must produce one signal, not one per tick it stays in a window. */
class StrategyEdgeTriggerTest {

    private static MarketSnapshot live(double px) {
        return new MarketSnapshot("SPY", px - 0.01, px + 0.01, px, 2000L,
                Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static StrategyExecutionLoop loop(PositionTracker tracker) {
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e12, 100_000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.strict());
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1e12, 1e9, 1e9, 1e9, 1e9);
        return new StrategyExecutionLoop("SPY", manager, admission, tracker, 10.0, 0.004);
    }

    private static int signals(StrategyExecutionLoop loop, double... prices) {
        int total = 0;
        for (double px : prices) {
            total += loop.onPrice(px, live(px)).totalSignals();
        }
        return total;
    }

    @Test
    void oneJumpProducesExactlyOneSignalAndOneOrder() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        StrategyExecutionLoop loop = loop(tracker);

        // 100 -> 100.5 is +0.5% (>= 0.4% trigger). The price then stays at 100.5 for many ticks.
        int total = signals(loop, 100.0, 100.0, 100.5, 100.5, 100.5, 100.5, 100.5, 100.5, 100.5, 100.5, 100.5, 100.5);

        assertEquals(1, total, "a single move must not re-fire while the price is unchanged");
        assertEquals(10, tracker.getNetQuantity("SPY"));
    }

    @Test
    void eachNewMoveSignalsOnceAndDirectionFollowsTheMove() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        StrategyExecutionLoop loop = loop(tracker);

        assertEquals(0, signals(loop, 100.0), "the first price has nothing to compare with");
        assertEquals(1, signals(loop, 101.0));
        assertEquals(10, tracker.getNetQuantity("SPY"));
        assertEquals(1, signals(loop, 99.0), "a drop sells");
        assertEquals(0, tracker.getNetQuantity("SPY"));
        assertEquals(0, signals(loop, 99.0, 99.0, 99.01), "sub-threshold noise is not a signal");
    }
}
