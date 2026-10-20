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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closing a straddle in one order. Found live on 2026-10-20: the strategy's close was two separate sells, and on the second
 * run the call sell timed out while the put sold, leaving a lone long call for about 18 s. The multi-leg order sent every leg
 * as "to open", so a multi-leg sell looked like writing an uncovered option and Alpaca refused it (403, account not eligible
 * to trade uncovered option contracts); a close must say "to close". Alpaca's documentation prices a multi-leg credit as a
 * negative limit price. These tests cover the request, the transport's rules, the order manager's decision about which legs
 * close, and the strategy's atomic close with its fall-back to the old leg-by-leg close.
 */
class AtomicCloseTest {

    private static final String CALL = "SPY261120C00769000";
    private static final String PUT = "SPY261120P00769000";
    private static final String ID = "37c008f4-5fa1-444f-b145-b7d558b35c69";

    private static final class SteppingClock extends Clock {
        Instant now = Instant.parse("2026-10-20T14:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static AlpacaPaperTransport transport(FakeAlpaca fake, double cushionBps) {
        SteppingClock clock = new SteppingClock();
        AlpacaPaperTransport t = new AlpacaPaperTransport(new AlpacaPaperClient(fake), Duration.ofSeconds(2), Duration.ofMillis(500), clock,
                d -> clock.now = clock.now.plus(d));
        t.setLimitCushionBps(cushionBps);
        return t;
    }

    private static String filledMleg(String netPrice, String side) {
        String leg = "{\"symbol\":\"%s\",\"side\":\"" + side + "\",\"qty\":\"1\",\"filled_qty\":\"1\",\"filled_avg_price\":\"%s\",\"status\":\"filled\"}";
        return "{\"id\":\"" + ID + "\",\"client_order_id\":\"bt-1\",\"symbol\":\"\",\"qty\":\"1\",\"filled_qty\":\"1\",\"filled_avg_price\":\"" + netPrice
                + "\",\"order_class\":\"mleg\",\"status\":\"filled\",\"legs\":[" + String.format(leg, CALL, "11.94") + "," + String.format(leg, PUT, "12.46") + "]}";
    }

    private static List<Order> sells() { return List.of(new Order(0, false, 1, 12.0), new Order(0, false, 1, 12.5)); }
    private static List<Order> buys() { return List.of(new Order(0, true, 1, 12.0), new Order(0, true, 1, 12.5)); }

    // ------------------------------------------------------------------ the request

    @Test
    void aClosingLegSaysToCloseAndAnOpeningLegStillSaysToOpen() {
        FakeAlpaca fake = new FakeAlpaca().post(200, filledMleg("24.40", "sell"));

        new AlpacaPaperClient(fake).submitMultiLegOrder(
                List.of(new AlpacaPaperClient.OptionLeg(CALL, 1, false, true), new AlpacaPaperClient.OptionLeg(PUT, 1, false, true)), 1, -24.40, "c-1");

        String request = fake.calls.get(0);
        assertEquals(2, request.split("\"position_intent\":\"sell_to_close\"", -1).length - 1, request);
        assertTrue(request.contains("\"limit_price\":\"-24.40\""), "a credit is a negative net price: " + request);

        FakeAlpaca open = new FakeAlpaca().post(200, filledMleg("24.59", "buy"));
        new AlpacaPaperClient(open).submitMultiLegOrder(
                List.of(new AlpacaPaperClient.OptionLeg(CALL, 1, true), new AlpacaPaperClient.OptionLeg(PUT, 1, true)), 1, 24.59, "o-1");
        assertEquals(2, open.calls.get(0).split("\"position_intent\":\"buy_to_open\"", -1).length - 1, open.calls.get(0));

        FakeAlpaca buyBack = new FakeAlpaca().post(200, filledMleg("24.59", "buy"));
        new AlpacaPaperClient(buyBack).submitMultiLegOrder(
                List.of(new AlpacaPaperClient.OptionLeg(CALL, 1, true, true), new AlpacaPaperClient.OptionLeg(PUT, 1, true, true)), 1, 24.59, "b-1");
        assertTrue(buyBack.calls.get(0).contains("buy_to_close"), buyBack.calls.get(0));
    }

    // ------------------------------------------------------------------ the transport

    @Test
    void theTransportSendsAClosingSellAsOneOrderAtANegativePriceAndReportsAPositiveProceeds() {
        FakeAlpaca fake = new FakeAlpaca().post(200, filledMleg("-24.40", "sell"));

        ExecutionResult r = transport(fake, 0.0).transmitMultiLeg(sells(), List.of(CALL, PUT), List.of(11.9, 12.4), List.of(12.0, 12.5), 24.40, List.of(true, true));

        assertTrue(r.executed(), r.message());
        assertEquals(1, fake.calls.size(), "one order, not one per leg");
        assertTrue(fake.calls.get(0).contains("\"limit_price\":\"-24.40\"") && fake.calls.get(0).contains("sell_to_close"), fake.calls.get(0));
        assertEquals(24.40, r.executionPrice(), 1e-9, "proceeds are reported as a positive amount whichever sign Alpaca uses");
        assertEquals(List.of(11.94, 12.46), r.legPrices());
    }

    @Test
    void aCushionOnACreditAsksForLessNotMore() {
        FakeAlpaca fake = new FakeAlpaca().post(200, filledMleg("24.40", "sell"));

        transport(fake, 30.0).transmitMultiLeg(sells(), List.of(CALL, PUT), List.of(11.9, 12.4), List.of(12.0, 12.5), 24.40, List.of(true, true));

        // 24.40 x 0.997 = 24.3268 -> down to the cent, 24.32: it will accept a little less, and the credit is sent negative
        assertTrue(fake.calls.get(0).contains("\"limit_price\":\"-24.32\""), fake.calls.get(0));
    }

    @Test
    void aSellThatWouldOpenAPositionOrAMixOfSidesIsStillRefusedBeforeItReachesTheVenue() {
        FakeAlpaca fake = new FakeAlpaca();
        AlpacaPaperTransport t = transport(fake, 0.0);

        ExecutionResult uncovered = t.transmitMultiLeg(sells(), List.of(CALL, PUT), List.of(11.9, 12.4), List.of(12.0, 12.5), 24.40, List.of(false, false));
        ExecutionResult oneLegOpens = t.transmitMultiLeg(sells(), List.of(CALL, PUT), List.of(11.9, 12.4), List.of(12.0, 12.5), 24.40, List.of(true, false));
        ExecutionResult mixed = t.transmitMultiLeg(List.of(new Order(0, true, 1, 12.0), new Order(0, false, 1, 12.5)), List.of(CALL, PUT),
                List.of(11.9, 12.4), List.of(12.0, 12.5), 0.50, List.of(true, true));

        assertFalse(uncovered.executed());
        assertTrue(uncovered.message().contains("uncovered") || uncovered.message().contains("open"), uncovered.message());
        assertFalse(oneLegOpens.executed());
        assertFalse(mixed.executed());
        assertEquals(0, fake.calls.size(), "nothing may leave for the venue");
    }

    @Test
    void aBuyBackOfAShortStraddleCloses() {
        FakeAlpaca fake = new FakeAlpaca().post(200, filledMleg("24.59", "buy"));

        ExecutionResult r = transport(fake, 0.0).transmitMultiLeg(buys(), List.of(CALL, PUT), List.of(11.9, 12.4), List.of(12.0, 12.5), 24.59, List.of(true, true));

        assertTrue(r.executed(), r.message());
        assertTrue(fake.calls.get(0).contains("buy_to_close") && fake.calls.get(0).contains("\"limit_price\":\"24.59\""), fake.calls.get(0));
    }

    // ------------------------------------------------------------------ the order manager

    private static final Instrument CALL_769 = new Instrument("SPY", LocalDate.of(2026, 11, 20), 769.0, OptionType.CALL, 100.0, null, 0.01, true);
    private static final Instrument PUT_769 = new Instrument("SPY", LocalDate.of(2026, 11, 20), 769.0, OptionType.PUT, 100.0, null, 0.01, true);

    private static MarketSnapshot quote(Instrument contract, double bid, double ask) {
        Instant now = Instant.now();
        return new MarketSnapshot(contract.contractSymbol(), bid, ask, (bid + ask) / 2, 500L, now, now, 0L, "ALPACA_INDICATIVE", MarketDataStatus.DELAYED, contract);
    }

    /** Accepts everything and remembers what multi-leg orders asked for. */
    private static final class RecordingTransport implements ExchangeTransport {
        final List<List<Boolean>> closingSeen = new ArrayList<>();
        boolean acceptMultiLeg = true;
        int singleLegOrders;

        @Override public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
            singleLegOrders++;
            return new ExecutionResult(symbol, order.quantity(), order.price(), true, "Executed");
        }

        @Override public ExecutionResult transmitMultiLeg(List<Order> legs, List<String> symbols, List<Double> bids, List<Double> asks,
                                                          double net, List<Boolean> closing) {
            closingSeen.add(closing);
            if (!acceptMultiLeg) {
                return new ExecutionResult(symbols.get(0), 0, 0.0, false, "Alpaca order x canceled, nothing filled: no multi-leg fill within 10 s");
            }
            return new ExecutionResult(symbols.get(0), legs.get(0).quantity(), net, true, "Multi-leg executed");
        }
    }

    private static OrderManager manager(PositionTracker tracker, RecordingTransport transport) {
        return new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000), transport, tracker,
                OrderManager.MarketDataPolicy.strict(), new TradingHalt(), null, 1);
    }

    @Test
    void theOrderManagerTellsTheTransportWhichLegsCloseAPosition() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        RecordingTransport transport = new RecordingTransport();
        OrderManager manager = manager(tracker, transport);
        MarketSnapshot call = quote(CALL_769, 12.0, 12.1), put = quote(PUT_769, 12.5, 12.6);

        assertTrue(manager.submitMultiLeg(buys(), List.of(call, put), 24.60).accepted(), "opening both legs");
        assertTrue(manager.submitMultiLeg(sells(), List.of(call, put), 24.40).accepted(), "closing both legs");

        assertEquals(List.of(List.of(false, false), List.of(true, true)), transport.closingSeen);
        assertEquals(0, tracker.getNetQuantity(CALL));
        assertEquals(0, tracker.getNetQuantity(PUT));
    }

    @Test
    void aSellOfOneLegHeldAndOneNotIsNotACloseOfBoth() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        RecordingTransport transport = new RecordingTransport();
        OrderManager manager = manager(tracker, transport);
        assertTrue(manager.submit(new Order(1, true, 1, 12.1), quote(CALL_769, 12.0, 12.1)).accepted(), "only the call is held");

        manager.submitMultiLeg(sells(), List.of(quote(CALL_769, 12.0, 12.1), quote(PUT_769, 12.5, 12.6)), 24.40);

        assertEquals(List.of(List.of(true, false)), transport.closingSeen, "the put sell would open a short");
    }
}
