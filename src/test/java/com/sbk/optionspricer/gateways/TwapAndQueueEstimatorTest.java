package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import com.sbk.optionspricer.core.OrderBookTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TwapAndQueueEstimatorTest {

    @Test
    @DisplayName("TwapExecutionAlgo evenly divides parent order without remainder")
    void testTwapEvenSplit() {
        OrderTicket parent = new OrderTicket("ORD-101", "SPY", OrderTicket.Side.BUY, 100, 500.0, OrderTicket.OrderType.LIMIT);
        Instant now = Instant.parse("2026-10-06T10:00:00Z");
        Duration duration = Duration.ofMinutes(10);
        int slices = 5;

        List<TwapExecutionAlgo.ScheduledOrder> schedule = TwapExecutionAlgo.generateSchedule(parent, now, duration, slices);

        assertEquals(5, schedule.size());
        int totalQty = 0;
        for (int i = 0; i < schedule.size(); i++) {
            TwapExecutionAlgo.ScheduledOrder so = schedule.get(i);
            assertEquals("ORD-101-TWAP-" + i, so.order().getOrderId());
            assertEquals("SPY", so.order().getSymbol());
            assertEquals(OrderTicket.Side.BUY, so.order().getSide());
            assertEquals(20, so.order().getQuantity());
            assertEquals(500.0, so.order().getPrice());
            assertEquals(now.plus(Duration.ofMinutes(2 * i)), so.executionTime());
            totalQty += so.order().getQuantity();
        }
        assertEquals(100, totalQty);
    }

    @Test
    @DisplayName("TwapExecutionAlgo handles remainder distribution correctly")
    void testTwapRemainderSplit() {
        OrderTicket parent = new OrderTicket("ORD-102", "AAPL", OrderTicket.Side.SELL, 10, 150.0, OrderTicket.OrderType.MARKET);
        Instant now = Instant.now();
        Duration duration = Duration.ofSeconds(30);
        int slices = 3;

        List<TwapExecutionAlgo.ScheduledOrder> schedule = TwapExecutionAlgo.generateSchedule(parent, now, duration, slices);

        assertEquals(3, schedule.size());
        assertEquals(4, schedule.get(0).order().getQuantity());
        assertEquals(3, schedule.get(1).order().getQuantity());
        assertEquals(3, schedule.get(2).order().getQuantity());
        int totalQty = schedule.stream().mapToInt(s -> s.order().getQuantity()).sum();
        assertEquals(10, totalQty);
    }

    @Test
    @DisplayName("TwapExecutionAlgo clamps slice count when sliceCount > parent quantity")
    void testTwapClampsSlicesToQuantity() {
        OrderTicket parent = new OrderTicket("ORD-103", "QQQ", OrderTicket.Side.BUY, 3, 400.0, OrderTicket.OrderType.LIMIT);
        List<TwapExecutionAlgo.ScheduledOrder> schedule = TwapExecutionAlgo.generateSchedule(parent, Instant.now(), Duration.ofMinutes(1), 10);

        assertEquals(3, schedule.size());
        for (TwapExecutionAlgo.ScheduledOrder so : schedule) {
            assertEquals(1, so.order().getQuantity());
        }
    }

    @Test
    @DisplayName("TwapExecutionAlgo rejects non-positive slice counts")
    void testTwapRejectsInvalidSliceCount() {
        OrderTicket parent = new OrderTicket("ORD-104", "SPY", OrderTicket.Side.BUY, 10, 500.0, OrderTicket.OrderType.LIMIT);
        assertThrows(IllegalArgumentException.class, () ->
                TwapExecutionAlgo.generateSchedule(parent, Instant.now(), Duration.ofMinutes(1), 0));
        assertThrows(IllegalArgumentException.class, () ->
                TwapExecutionAlgo.generateSchedule(parent, Instant.now(), Duration.ofMinutes(1), -5));
    }

    @Test
    @DisplayName("QueuePositionEstimator estimates fill probability across regimes")
    void testQueuePositionEstimator() {
        // Zero or negative volume ahead gives 1.0 probability
        QueuePositionEstimator.QueueState stateZero = QueuePositionEstimator.estimateQueuePosition(0, 0, 10.0, 5.0, 1.0);
        assertEquals(1.0, stateZero.fillProbability, 1e-6);
        assertEquals(0, stateZero.ordersAhead);
        assertEquals(0, stateZero.volumeAhead);

        // Expected cleared exceeds volume ahead
        // cleared = (10 + 0.5 * 4) * 2 = 24. volumeAhead = 20.
        QueuePositionEstimator.QueueState stateExcess = QueuePositionEstimator.estimateQueuePosition(5, 20, 10.0, 4.0, 2.0);
        assertTrue(stateExcess.fillProbability >= 0.8 && stateExcess.fillProbability <= 1.0);
        assertEquals(5, stateExcess.ordersAhead);
        assertEquals(20, stateExcess.volumeAhead);

        // Expected cleared is less than volume ahead
        // cleared = (5 + 0.5 * 2) * 2 = 12. volumeAhead = 40. fillProb = 12 / 40 = 0.3
        QueuePositionEstimator.QueueState stateDeficit = QueuePositionEstimator.estimateQueuePosition(10, 40, 5.0, 2.0, 2.0);
        assertEquals(0.3, stateDeficit.fillProbability, 1e-4);
    }

    @Test
    @DisplayName("OrderTicket lifecycle and mutations")
    void testOrderTicketLifecycle() {
        OrderTicket ticket = new OrderTicket("ORD-201", "MSFT", OrderTicket.Side.BUY, 50, 420.0, OrderTicket.OrderType.LIMIT);
        assertEquals("ORD-201", ticket.getOrderId());
        assertEquals("MSFT", ticket.getSymbol());
        assertEquals(OrderTicket.Side.BUY, ticket.getSide());
        assertEquals(50, ticket.getQuantity());
        assertEquals(420.0, ticket.getPrice(), 1e-6);
        assertEquals(OrderTicket.OrderType.LIMIT, ticket.getType());
        assertEquals(OrderState.NEW, ticket.getState());
        assertEquals(0, ticket.getFilledQuantity());

        // Partial fill
        ticket.addFill(20);
        assertEquals(20, ticket.getFilledQuantity());
        assertEquals(OrderState.PARTIALLY_FILLED, ticket.getState());

        // Full fill
        ticket.addFill(35); // Overfills by 5
        assertEquals(50, ticket.getFilledQuantity());
        assertEquals(OrderState.FILLED, ticket.getState());

        // Custom state transition
        ticket.setState(OrderState.CANCELED);
        assertEquals(OrderState.CANCELED, ticket.getState());

        // Error cases
        assertThrows(IllegalArgumentException.class, () -> ticket.addFill(0));
        assertThrows(IllegalArgumentException.class, () -> ticket.addFill(-10));
        assertThrows(IllegalArgumentException.class, () ->
                new OrderTicket("ORD-202", "MSFT", OrderTicket.Side.BUY, 0, 100.0, OrderTicket.OrderType.LIMIT));
    }

    @Test
    @DisplayName("HistoricalReplayEngine streams CSV into EobiDecoder and RingBuffer")
    void testHistoricalReplayEngine() throws IOException {
        MarketDataRingBuffer ringBuffer = new MarketDataRingBuffer(128);
        EobiDecoder decoder = new EobiDecoder(ringBuffer);
        HistoricalReplayEngine replayEngine = new HistoricalReplayEngine(decoder, "market_data.csv");

        replayEngine.start();

        OrderBookTick tick = ringBuffer.poll();
        assertNotNull(tick, "Ring buffer should contain ticks replayed from market_data.csv");
        assertTrue(tick.getInstrumentId() > 0);
        assertTrue(tick.getBidPrice() > 0.0);
        assertTrue(tick.getAskPrice() > 0.0);
        assertTrue(tick.getAskPrice() >= tick.getBidPrice());
    }
}
