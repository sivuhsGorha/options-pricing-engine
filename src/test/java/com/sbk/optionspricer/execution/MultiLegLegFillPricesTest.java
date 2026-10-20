package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.Instrument;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found live on 2026-10-20: the first filled multi-leg straddle came back from Alpaca with a net price of 25.27 and, inside
 * the order, the price each leg actually filled at (call 12.07, put 13.20). The engine ignored the leg prices and split the
 * net in proportion to its own limit prices, booking 12.13 and 13.14: the total agreed, each leg's cost did not. The legs'
 * own fill prices are now carried through and booked when they are there and add up to the net; otherwise the proportional
 * split stands.
 */
class MultiLegLegFillPricesTest {

    private static final String CALL = "SPY261120C00769000";
    private static final String PUT = "SPY261120P00769000";
    private static final String ID = "37c008f4-5fa1-444f-b145-b7d558b35c69";

    /** The shape Alpaca returns for a filled mleg order: the net price on the order, each leg's own on its leg. */
    private static String filledMlegJson(String callPrice, String putPrice, boolean putFirst) {
        String call = String.format("{\"symbol\":\"%s\",\"side\":\"buy\",\"qty\":\"1\",\"filled_qty\":\"1\",\"filled_avg_price\":%s,\"status\":\"filled\"}", CALL, callPrice);
        String put = String.format("{\"symbol\":\"%s\",\"side\":\"buy\",\"qty\":\"1\",\"filled_qty\":\"1\",\"filled_avg_price\":%s,\"status\":\"filled\"}", PUT, putPrice);
        return "{\"id\":\"" + ID + "\",\"client_order_id\":\"bt-1\",\"symbol\":\"\",\"qty\":\"1\",\"filled_qty\":\"1\",\"filled_avg_price\":\"25.27\","
                + "\"order_class\":\"mleg\",\"status\":\"filled\",\"legs\":[" + (putFirst ? put + "," + call : call + "," + put) + "]}";
    }

    private static final class SteppingClock extends Clock {
        Instant now = Instant.parse("2026-10-20T14:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static ExecutionResult send(String orderJson) {
        SteppingClock clock = new SteppingClock();
        AlpacaPaperTransport transport = new AlpacaPaperTransport(new AlpacaPaperClient(new FakeAlpaca().post(200, orderJson)),
                Duration.ofSeconds(2), Duration.ofMillis(500), clock, d -> clock.now = clock.now.plus(d));
        return transport.transmitMultiLeg(List.of(new Order(0, true, 1, 12.60), new Order(0, true, 1, 12.60)),
                List.of(CALL, PUT), List.of(12.5, 12.5), List.of(12.6, 12.6), 25.21);
    }

    @Test
    void theTransportReturnsEachLegsOwnFillPriceInTheOrderTheLegsWereSent() {
        ExecutionResult r = send(filledMlegJson("\"12.07\"", "\"13.2\"", false));

        assertTrue(r.executed(), r.message());
        assertEquals(25.27, r.executionPrice(), 1e-9);
        assertEquals(List.of(12.07, 13.20), r.legPrices());

        // Alpaca does not promise the legs come back in the order they were sent
        assertEquals(List.of(12.07, 13.20), send(filledMlegJson("\"12.07\"", "\"13.2\"", true)).legPrices(),
                "matched by contract symbol, not by position");
    }

    @Test
    void aLegWithoutAFillPriceMeansNoLegPricesAreReported() {
        ExecutionResult r = send(filledMlegJson("null", "\"13.2\"", false));

        assertTrue(r.executed(), r.message());
        assertTrue(r.legPrices().isEmpty(), "never half-known: " + r.legPrices());
    }

    // ------------------------------------------------------------------ booking

    private static final Instrument CALL_769 = new Instrument("SPY", LocalDate.of(2026, 11, 20), 769.0, OptionType.CALL, 100.0, null, 0.01, true);
    private static final Instrument PUT_769 = new Instrument("SPY", LocalDate.of(2026, 11, 20), 769.0, OptionType.PUT, 100.0, null, 0.01, true);

    private static MarketSnapshot quote(Instrument contract, double bid, double ask) {
        Instant now = Instant.now();
        return new MarketSnapshot(contract.contractSymbol(), bid, ask, (bid + ask) / 2, 500L, now, now, 0L, "ALPACA_INDICATIVE", MarketDataStatus.DELAYED, contract);
    }

    private static PositionTracker bookAfter(ExecutionResult transportResult) {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                new ExchangeTransport() {
                    @Override public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
                        throw new AssertionError("single-leg path not used");
                    }
                    @Override public ExecutionResult transmitMultiLeg(List<Order> legs, List<String> symbols, List<Double> bids, List<Double> asks, double net) {
                        return transportResult;
                    }
                },
                tracker, OrderManager.MarketDataPolicy.strict(), new TradingHalt(), null, 1);
        OrderManager.OrderDecision d = manager.submitMultiLeg(
                List.of(new Order(1, true, 1, 12.60), new Order(2, true, 1, 12.60)),
                List.of(quote(CALL_769, 12.5, 12.6), quote(PUT_769, 12.5, 12.6)), 25.20);
        assertTrue(d.accepted(), d.message());
        return tracker;
    }

    @Test
    void theBookCarriesEachLegAtItsOwnVenueFillPriceWhenTheyAddUpToTheNet() {
        PositionTracker tracker = bookAfter(new ExecutionResult(CALL, 1, 25.27, true, "filled", List.of(12.07, 13.20)));

        assertEquals(12.07 * 100, tracker.getNotional(CALL), 1e-6);
        assertEquals(13.20 * 100, tracker.getNotional(PUT), 1e-6);
    }

    @Test
    void withoutLegPricesOrWhenTheyDoNotAddUpTheNetIsSplitProportionallyAsBefore() {
        PositionTracker noLegs = bookAfter(new ExecutionResult(CALL, 1, 25.27, true, "filled"));
        assertEquals(25.27 * 100, noLegs.getNotional(CALL) + noLegs.getNotional(PUT), 1e-6, "the total is the net either way");
        assertEquals(noLegs.getNotional(CALL), noLegs.getNotional(PUT), 1e-6, "equal limits split the net equally");

        // legs that do not add up to the net are not believed: 12.07 + 13.80 = 25.87, not 25.27
        PositionTracker mismatch = bookAfter(new ExecutionResult(CALL, 1, 25.27, true, "filled", List.of(12.07, 13.80)));
        assertEquals(25.27 * 100, mismatch.getNotional(CALL) + mismatch.getNotional(PUT), 1e-6);
        assertEquals(mismatch.getNotional(CALL), mismatch.getNotional(PUT), 1e-6);
    }
}
