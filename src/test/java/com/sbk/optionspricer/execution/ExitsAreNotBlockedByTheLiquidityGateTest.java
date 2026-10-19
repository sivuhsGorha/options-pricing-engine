package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.Instrument;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.FillRecorder;
import com.sbk.optionspricer.risk.LiquidityRiskMonitor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found live on 2026-10-19: a straddle's second leg was refused for a 204 bps spread, and the order to sell the first leg
 * back was refused by the same gate at 300 bps, so the engine sat on a lone long call until a later retry found a tighter
 * quote. The spread and volume limits are a test of the quality of an entry. An order that only reduces a position must not
 * be stopped by them, or a position can be trapped while the market is wide. Size, price, concentration and rate limits
 * still apply to every order.
 */
class ExitsAreNotBlockedByTheLiquidityGateTest {

    private static final Instant NOW = Instant.now();
    private static final Instrument CALL = new Instrument("SPY", LocalDate.of(2026, 11, 20), 771.0, OptionType.CALL, 100.0, null, 0.01, true);
    private static final String SYMBOL = "SPY261120C00771000";

    private static PreTradeRiskFilter filter() {
        return new PreTradeRiskFilter(10, 1e9, 100, Map.of(), new ConcentrationLimitManager(Map.of("SPY", 1e9)),
                new LiquidityRiskMonitor(200.0, 1L));
    }

    private static MarketSnapshot quote(double bid, double ask, long volume) {
        return new MarketSnapshot(SYMBOL, bid, ask, (bid + ask) / 2, volume, NOW, NOW, 0L, "ALPACA_INDICATIVE", MarketDataStatus.DELAYED, CALL);
    }

    private static OrderManager manager(PositionTracker tracker) {
        return new OrderManager(filter(),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.strict(), new TradingHalt(), null, 1);
    }

    private static final double WIDE_BID = 11.83, WIDE_ASK = 12.19; // 300 bps, as seen live

    @Test
    void theFilterStopsAWideEntryButLetsAnExitThrough() {
        PreTradeRiskFilter filter = filter();
        Order order = new Order(1, false, 1, 11.90);

        assertFalse(filter.checkRisk(order, "SPY", WIDE_BID, WIDE_ASK, 500L, 100, false), "an entry at 300 bps is refused");
        assertTrue(filter.checkRisk(order, "SPY", WIDE_BID, WIDE_ASK, 500L, 100, true), "an exit at 300 bps goes through");
        assertTrue(filter.checkRisk(order, "SPY", WIDE_BID, WIDE_ASK, 0L, 100, true), "and so does one in a contract with no volume yet");
    }

    @Test
    void anExitIsStillHeldToSizePriceAndNotionalLimits() {
        PreTradeRiskFilter filter = filter();

        assertFalse(filter.checkRisk(new Order(1, false, 11, 11.90), "SPY", 11.89, 11.91, 500L, 100, true), "over the order-size limit");
        assertFalse(filter.checkRisk(new Order(1, false, 1, 0.0), "SPY", 11.89, 11.91, 500L, 100, true), "no price");
        assertFalse(filter.checkRisk(new Order(1, false, 0, 11.90), "SPY", 11.89, 11.91, 500L, 100, true), "no quantity");
    }

    @Test
    void theOrderManagerLetsALoneLongCallBeSoldBackWhileTheMarketIsWide() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = manager(tracker);
        assertTrue(manager.submit(new Order(1, true, 1, 12.00), quote(11.95, 12.05, 500L)).accepted(), "bought while the market was tight");

        OrderManager.OrderDecision sell = manager.submit(new Order(2, false, 1, 11.90), quote(WIDE_BID, WIDE_ASK, 500L));

        assertTrue(sell.accepted(), sell.message());
        assertEquals(0, tracker.getNetQuantity(SYMBOL));
    }

    @Test
    void anOrderThatOpensOrGrowsOrFlipsAPositionIsStillAnEntryAndStillStopped() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = manager(tracker);

        assertFalse(manager.submit(new Order(1, true, 1, 12.00), quote(WIDE_BID, WIDE_ASK, 500L)).accepted(), "opening, flat");
        assertTrue(manager.submit(new Order(2, true, 1, 12.00), quote(11.95, 12.05, 500L)).accepted());
        assertFalse(manager.submit(new Order(3, true, 1, 12.00), quote(WIDE_BID, WIDE_ASK, 500L)).accepted(), "adding to a long");
        assertFalse(manager.submit(new Order(4, false, 2, 11.90), quote(WIDE_BID, WIDE_ASK, 500L)).accepted(),
                "selling 2 against 1 long would flip the position short: the extra contract is a new entry");
        assertEquals(1, tracker.getNetQuantity(SYMBOL));
    }

    @Test
    void aShortCanBeBoughtBackWhileTheMarketIsWideButNotAddedTo() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = manager(tracker);
        assertTrue(manager.submit(new Order(1, false, 1, 12.00), quote(11.95, 12.05, 500L)).accepted(), "sold short while tight");

        assertFalse(manager.submit(new Order(2, false, 1, 11.90), quote(WIDE_BID, WIDE_ASK, 500L)).accepted(), "more short is an entry");
        assertTrue(manager.submit(new Order(3, true, 1, 12.20), quote(WIDE_BID, WIDE_ASK, 500L)).accepted(), "buying it back is an exit");
        assertEquals(0, tracker.getNetQuantity(SYMBOL));
    }

    @Test
    void eachLegOfAMultiLegOrderIsJudgedOnItsOwnPosition() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = manager(tracker);
        assertTrue(manager.submit(new Order(1, true, 1, 12.00), quote(11.95, 12.05, 500L)).accepted());

        OrderManager.OrderDecision entry = manager.submitMultiLeg(
                List.of(new Order(2, true, 1, 12.00)), List.of(quote(WIDE_BID, WIDE_ASK, 500L)), 12.00);
        assertFalse(entry.accepted(), "adding a long leg at 300 bps is still refused");

        OrderManager.OrderDecision exit = manager.submitMultiLeg(
                List.of(new Order(3, false, 1, 11.90)), List.of(quote(WIDE_BID, WIDE_ASK, 500L)), 11.90);
        assertTrue(exit.accepted(), exit.message());
    }
}
