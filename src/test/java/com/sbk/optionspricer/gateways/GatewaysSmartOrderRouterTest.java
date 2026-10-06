package com.sbk.optionspricer.gateways;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class GatewaysSmartOrderRouterTest {

    @Test
    void testValidRouting() {
        double[] weights = { 0.5, 0.3, 0.2 };
        List<SmartOrderRouter.SubOrder> orders = SmartOrderRouter.routeOrder(100, weights);
        assertEquals(3, orders.size());
        assertEquals(50, orders.get(0).allocatedQty);
        assertEquals(30, orders.get(1).allocatedQty);
        assertEquals(20, orders.get(2).allocatedQty);
    }

    @Test
    void testInvalidQuantity() {
        double[] weights = { 0.5, 0.3, 0.2 };
        assertThrows(IllegalArgumentException.class, () -> SmartOrderRouter.routeOrder(0, weights));
        assertThrows(IllegalArgumentException.class, () -> SmartOrderRouter.routeOrder(-10, weights));
    }

    @Test
    void testInvalidWeights() {
        assertThrows(IllegalArgumentException.class, () -> SmartOrderRouter.routeOrder(100, null));
        assertThrows(IllegalArgumentException.class, () -> SmartOrderRouter.routeOrder(100, new double[]{0.5, 0.5})); // Wrong length
        assertThrows(IllegalArgumentException.class, () -> SmartOrderRouter.routeOrder(100, new double[]{0.5, -0.3, 0.8})); // Negative
        assertThrows(IllegalArgumentException.class, () -> SmartOrderRouter.routeOrder(100, new double[]{0.5, Double.NaN, 0.5})); // NaN
        assertThrows(IllegalArgumentException.class, () -> SmartOrderRouter.routeOrder(100, new double[]{0.0, 0.0, 0.0})); // Zero total weight
    }
}
