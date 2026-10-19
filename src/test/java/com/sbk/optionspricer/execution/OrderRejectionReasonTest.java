package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found live on 2026-10-19: a limit order that Alpaca accepted and that simply did not fill within the wait was reported to the
 * strategy, the log and the journal as "execution transport rejected the order", the same words as a venue refusal. The
 * transport had written exactly what happened (the limit price and where it came from, that nothing filled, that the remainder
 * was cancelled), but only the audit trail kept it. The reason now travels with the decision.
 */
class OrderRejectionReasonTest {

    private static final String NO_FILL = "Alpaca order abc-123 canceled, nothing filled: no fill within 10 s at limit 12.04 (the feed's ask); remainder cancelled at Alpaca";

    private static MarketSnapshot stock() {
        return new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static OrderManager managerWhoseTransportSays(String message) {
        return new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, 0, 0.0, false, message),
                new PositionTracker(FillRecorder.NONE), OrderManager.MarketDataPolicy.strict(), new TradingHalt(), null, 1);
    }

    @Test
    void theDecisionCarriesWhatTheTransportSaidSoANoFillIsNotMistakenForARefusal() {
        OrderManager manager = managerWhoseTransportSays(NO_FILL);

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 1, 100.0), stock());

        assertFalse(decision.accepted());
        assertTrue(decision.message().startsWith("execution transport rejected the order"), decision.message());
        assertTrue(decision.message().contains("limit 12.04") && decision.message().contains("nothing filled"), decision.message());
        assertEquals(NO_FILL, manager.getAuditTrail().get(0).rejectionReason(), "the audit trail still has the full text");
    }

    @Test
    void aTransportWithNothingToSayStillGivesTheShortReason() {
        OrderManager manager = managerWhoseTransportSays("");

        OrderManager.OrderDecision decision = manager.submit(new Order(1, true, 1, 100.0), stock());

        assertEquals("execution transport rejected the order", decision.message());
    }
}
