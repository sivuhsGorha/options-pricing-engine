package com.sbk.optionspricer.gateways;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FixInjectionTest {

    @Test
    void encodesValidOrderTicketSuccessfully() {
        OrderTicket ticket = new OrderTicket("ORD100", "SPY", OrderTicket.Side.BUY, 50, 450.50, OrderTicket.OrderType.LIMIT);
        String fix = FixMessageEncoder.encodeNewOrderSingle(ticket);
        assertNotNull(fix);
        assertTrue(fix.startsWith("8=FIX.4.4\u00019="));
        assertTrue(fix.contains("\u000135=D\u0001"));
        assertTrue(fix.contains("\u000111=ORD100\u0001"));
        assertTrue(fix.contains("\u000155=SPY\u0001"));
    }

    @Test
    void rejectsFixInjectionWithSohDelimiter() {
        OrderTicket ticketWithInjectedSoh = new OrderTicket("ORD100\u000135=X", "SPY", OrderTicket.Side.BUY, 50, 450.50, OrderTicket.OrderType.LIMIT);
        assertThrows(IllegalArgumentException.class, () -> FixMessageEncoder.encodeNewOrderSingle(ticketWithInjectedSoh));

        OrderTicket ticketWithInjectedSymbol = new OrderTicket("ORD100", "SPY\u000154=2", OrderTicket.Side.BUY, 50, 450.50, OrderTicket.OrderType.LIMIT);
        assertThrows(IllegalArgumentException.class, () -> FixMessageEncoder.encodeNewOrderSingle(ticketWithInjectedSymbol));
    }

    @Test
    void rejectsFixInjectionWithEqualsSign() {
        OrderTicket ticketWithEqual = new OrderTicket("ORD100=evil", "SPY", OrderTicket.Side.BUY, 50, 450.50, OrderTicket.OrderType.LIMIT);
        assertThrows(IllegalArgumentException.class, () -> FixMessageEncoder.encodeNewOrderSingle(ticketWithEqual));
    }
}
