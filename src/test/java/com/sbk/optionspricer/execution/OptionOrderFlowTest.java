package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.Instrument;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An option order is booked per contract with the contract's multiplier and counts against its underlying. */
class OptionOrderFlowTest {

    private static final Instant NOW = Instant.now();
    private static final Instrument CALL_780 = new Instrument("SPY", LocalDate.of(2026, 11, 20), 780.0, OptionType.CALL, 100.0, null, 0.01, true);

    private static MarketSnapshot optionQuote(Instrument contract, double bid, double ask) {
        return new MarketSnapshot(contract.contractSymbol(), bid, ask, (bid + ask) / 2, 500L, NOW, NOW, 0L, "CBOE_DELAYED", MarketDataStatus.DELAYED, contract);
    }

    private static MarketSnapshot shareQuote(double last) {
        return new MarketSnapshot("SPY", last - 0.01, last + 0.01, last, 5000L, NOW, NOW, 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static OrderManager manager(PositionTracker tracker, double concentrationLimit) {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(1_000, 1e9, 1_000, Map.of(),
                new ConcentrationLimitManager(Map.of("SPY", concentrationLimit)), null);
        return new OrderManager(filter,
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.strict(), new TradingHalt(), null, 1);
    }

    @Test
    void anOptionFillIsBookedUnderItsContractWithTheContractMultiplier() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = manager(tracker, 1e9);

        OrderManager.OrderDecision decision = manager.submit(new Order(7, true, 2, 9.90), optionQuote(CALL_780, 9.80, 10.00));

        assertTrue(decision.accepted(), decision.message());
        assertEquals(2, tracker.getNetQuantity("SPY261120C00780000"));
        assertEquals(100, tracker.getPosition("SPY261120C00780000").getMultiplier(), "the contract's multiplier, not the configured 1");
        assertEquals(2 * 9.90 * 100, tracker.getNotional("SPY261120C00780000"), 1e-6);
        assertEquals(0, tracker.getNetQuantity("SPY"), "nothing is booked under the underlying's ticker");
    }

    @Test
    void optionNotionalCountsAgainstTheUnderlyingsConcentrationLimitTogetherWithShares() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = manager(tracker, 5_000.0);

        assertTrue(manager.submit(new Order(7, true, 2, 9.90), optionQuote(CALL_780, 9.80, 10.00)).accepted());  // 1,980 on SPY
        assertTrue(manager.submit(new Order(7, true, 2, 9.90), optionQuote(CALL_780, 9.80, 10.00)).accepted());  // 3,960
        assertFalse(manager.submit(new Order(7, true, 2, 9.90), optionQuote(CALL_780, 9.80, 10.00)).accepted(),
                "a third pair would take SPY exposure to 5,940 > 5,000");

        assertTrue(manager.submit(new Order(1, true, 10, 100.0), shareQuote(100.0)).accepted(), "10 shares: 4,960 on SPY");
        assertFalse(manager.submit(new Order(1, true, 1, 100.0), shareQuote(100.0)).accepted(),
                "one more share would exceed the same per-underlying limit the options count against");
        assertEquals(10, tracker.getNetQuantity("SPY"));
        assertEquals(1, tracker.getPosition("SPY").getMultiplier());
    }

    @Test
    void anOptionSnapshotMustCarryItsOwnContractSymbol() {
        assertThrows(IllegalArgumentException.class, () ->
                new MarketSnapshot("SPY", 9.80, 10.00, 9.90, 500L, NOW, NOW, 0L, "X", MarketDataStatus.DELAYED, CALL_780));
        MarketSnapshot ok = optionQuote(CALL_780, 9.80, 10.00);
        assertTrue(ok.isOption());
        assertEquals("SPY", ok.underlying());
        assertEquals(100, ok.multiplierOr(1));
        assertFalse(shareQuote(100.0).isOption());
        assertEquals(7, shareQuote(100.0).multiplierOr(7));
    }
}
