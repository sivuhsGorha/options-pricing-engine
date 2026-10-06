package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperTradingExecutionAdapterTest {

    @Test
    void acceptsOrderAndUpdatesPositionTracker() {
        PositionTracker tracker = new PositionTracker();
        PaperTradingExecutionAdapter adapter = new PaperTradingExecutionAdapter(tracker, 10.0d);

        Order order = new Order(1, true, 5, 100.0);
        ExecutionResult result = adapter.executeOrder(order, "SPY", 99.5, 100.5);

        assertTrue(result.executed());
        assertEquals("SPY", result.symbol());
        assertEquals(5, result.filledQuantity());
        assertEquals(0, tracker.getNetQuantity("SPY"));
    }
}
