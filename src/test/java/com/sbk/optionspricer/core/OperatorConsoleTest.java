package com.sbk.optionspricer.core;

import com.sbk.optionspricer.execution.ExecutionResult;
import com.sbk.optionspricer.execution.OperatorControls;
import com.sbk.optionspricer.execution.Order;
import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.execution.StrategySwitch;
import com.sbk.optionspricer.execution.TradingHalt;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** The operator can stop and restart trading, switch the strategy off, and always sees the resulting state. */
class OperatorConsoleTest {

    private static final class Switch implements StrategySwitch {
        boolean enabled = true;

        @Override
        public boolean isStrategyEnabled() {
            return enabled;
        }

        @Override
        public void setStrategyEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private static MarketSnapshot live() {
        return new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    @Test
    void haltRejectsOrdersUntilResumeAndTheStateSaysWhy() {
        TradingHalt halt = new TradingHalt();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.strict(), halt, null);
        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", manager, new PortfolioRiskAdmission(1e12, 1e12, 1e12, 1e12, 1e12), tracker, 10, 0.001);
        OperatorConsole console = new OperatorConsole(halt, new Switch(), loop);

        OperatorControls.ControlState halted = console.halt("end of day");
        assertTrue(halted.halted());
        assertEquals("operator: end of day", halted.haltReason());
        assertNotNull(halted.haltedAt());
        OrderManager.OrderDecision rejected = manager.submit(new Order(1, true, 1, 100.0), live());
        assertFalse(rejected.accepted());
        assertTrue(rejected.message().contains("halted"), rejected.message());

        OperatorControls.ControlState resumed = console.resume();
        assertFalse(resumed.halted());
        assertNull(resumed.haltReason());
        assertTrue(manager.submit(new Order(2, true, 1, 100.0), live()).accepted());

        assertEquals("SPY", resumed.symbol());
        assertEquals(0.001, resumed.triggerPct(), 1e-12);
        assertEquals(10, resumed.baseQuantity());
        assertTrue(console.halt("   ").haltReason().contains("operator"), "a blank reason is still attributed");
    }

    @Test
    void theStrategySwitchStopsOrderGenerationInTheHarness() {
        TradingHalt halt = new TradingHalt();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.allowSimulated(), halt, null);
        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", manager, new PortfolioRiskAdmission(1e12, 1e12, 1e12, 1e12, 1e12), tracker, 10, 0.0001);
        QuantSimulationHarness harness = new QuantSimulationHarness(null, null, tracker, manager, null, loop);
        OperatorConsole console = new OperatorConsole(halt, harness, loop);

        harness.runStrategyStep(100.0);
        assertEquals(1, harness.runStrategyStep(101.0).totalSignals(), "a 1% move with the strategy on is a signal");

        assertFalse(console.setStrategyEnabled(false).strategyEnabled());
        assertEquals(0, harness.runStrategyStep(103.0).totalSignals(), "the strategy is off: no signal however large the move");
        assertEquals(10, tracker.getNetQuantity("SPY"), "only the first order was filled");

        assertTrue(console.setStrategyEnabled(true).strategyEnabled());
        assertEquals(1, harness.runStrategyStep(105.0).totalSignals());
    }

    @Test
    void theConsoleNeedsAHaltAndASwitch() {
        assertThrows(IllegalArgumentException.class, () -> new OperatorConsole(null, new Switch(), null));
        assertThrows(IllegalArgumentException.class, () -> new OperatorConsole(new TradingHalt(), null, null));
        OperatorControls.ControlState bare = new OperatorConsole(new TradingHalt(), new Switch(), null).state();
        assertNull(bare.symbol());
        assertTrue(Double.isNaN(bare.triggerPct()));
    }
}
