package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SmartOrderRouterTest {

    @Test
    void testRouteOrderReturnsTransportResult() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        ExchangeTransport mockSuccessTransport = payload -> true;
        ExchangeTransport mockFailureTransport = payload -> false;

        SmartOrderRouter routerSuccess = new SmartOrderRouter(filter, mockSuccessTransport);
        assertTrue(routerSuccess.routeOrder(new Order(1, true, 10, 100.0)), "Order should return transport success");

        SmartOrderRouter routerFailure = new SmartOrderRouter(filter, mockFailureTransport);
        assertFalse(routerFailure.routeOrder(new Order(1, true, 10, 100.0)), "Order should return transport failure");
    }

    @Test
    void testRiskFilterBlockPreventsRoute() {
        PreTradeRiskFilter filter = new PreTradeRiskFilter(100, 100000.0, 100);
        // Transport that always returns true, but we expect the filter to block it.
        ExchangeTransport mockTransport = payload -> true;

        SmartOrderRouter router = new SmartOrderRouter(filter, mockTransport);
        assertFalse(router.routeOrder(new Order(1, true, -10, 100.0)), "Risk filter block should prevent route and return false");
    }
}
