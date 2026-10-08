package com.sbk.optionspricer.risk;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

public class PortfolioPositionTest {
    

    @Test
    void testInvalidSymbol() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PortfolioPosition("", 1, 100);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new PortfolioPosition(null, 1, 100);
        });
    }

    @Test
    void testInvalidMultiplier() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PortfolioPosition("SPY", 1, -1);
        });
    }

    @Test
    void testInvalidGreeks() {
        PortfolioPosition pos = new PortfolioPosition("SPY", 1, 100);
        assertThrows(IllegalArgumentException.class, () -> {
            pos.updateGreeks(Double.NaN, 0.0, 0.0);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            pos.updateGreeks(0.0, Double.POSITIVE_INFINITY, 0.0);
        });
    }

    @Test
    void testIntOverflow() {
        PortfolioPosition pos = new PortfolioPosition("SPY", Integer.MAX_VALUE, 100);
        assertThrows(ArithmeticException.class, () -> {
            pos.addQuantity(1);
        });
        
        pos.updateGreeks(1.0, 0.0, 0.0);
        assertThrows(ArithmeticException.class, () -> {
            pos.getPositionDelta();
        });
    }

}
