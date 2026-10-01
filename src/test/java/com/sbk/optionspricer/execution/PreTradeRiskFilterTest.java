package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PreTradeRiskFilterTest {

    @Test
    void testNegativeOrZeroQuantityRejected() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        assertFalse(filter.checkRisk(new Order(1, true, 0, 100.0)), "Zero quantity must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, -10, 100.0)), "Negative quantity must be rejected");
    }

    @Test
    void testNegativeOrZeroPriceRejected() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        assertFalse(filter.checkRisk(new Order(1, true, 10, 0.0)), "Zero price must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, 10, -50.0)), "Negative price must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, 10, Double.NaN)), "NaN price must be rejected");
        assertFalse(filter.checkRisk(new Order(1, true, 10, Double.POSITIVE_INFINITY)), "Infinity price must be rejected");
    }

    @Test
    void testValidOrderAccepted() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        assertTrue(filter.checkRisk(new Order(1, true, 10, 100.0)), "Valid order should be accepted");
    }
}
