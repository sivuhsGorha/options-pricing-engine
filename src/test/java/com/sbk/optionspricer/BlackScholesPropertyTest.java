package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BlackScholesPropertyTest {

    private static final double TOLERANCE = 1e-6;

    @Test
    void testPutCallParity() {
        double spot = 100.0;
        double strike = 105.0;
        double time = 1.0;
        double rate = 0.05;
        double div = 0.02;
        double vol = 0.20;

        double call = BlackScholesPricer.price(OptionType.CALL, spot, strike, time, rate, vol, div);
        double put = BlackScholesPricer.price(OptionType.PUT, spot, strike, time, rate, vol, div);

        // C - P = S*e^{-qT} - K*e^{-rT}
        double lhs = call - put;
        double rhs = spot * Math.exp(-div * time) - strike * Math.exp(-rate * time);

        assertEquals(rhs, lhs, TOLERANCE, "Put-Call Parity violated");
    }

    @Test
    void testMonotonicityWithSpot() {
        double spot1 = 100.0;
        double spot2 = 105.0;
        double strike = 100.0;
        
        double call1 = BlackScholesPricer.price(OptionType.CALL, spot1, strike, 1.0, 0.05, 0.20, 0.0);
        double call2 = BlackScholesPricer.price(OptionType.CALL, spot2, strike, 1.0, 0.05, 0.20, 0.0);
        
        assertTrue(call2 > call1, "Call price must increase with spot price");

        double put1 = BlackScholesPricer.price(OptionType.PUT, spot1, strike, 1.0, 0.05, 0.20, 0.0);
        double put2 = BlackScholesPricer.price(OptionType.PUT, spot2, strike, 1.0, 0.05, 0.20, 0.0);
        
        assertTrue(put2 < put1, "Put price must decrease with spot price");
    }

    @Test
    void testBounds() {
        double spot = 100.0;
        double strike = 100.0;
        double call = BlackScholesPricer.price(OptionType.CALL, spot, strike, 1.0, 0.05, 0.20, 0.0);
        
        assertTrue(call >= 0, "Option price cannot be negative");
        assertTrue(call <= spot, "Call price cannot exceed spot price");
    }
}
