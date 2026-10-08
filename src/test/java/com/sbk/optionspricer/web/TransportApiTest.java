package com.sbk.optionspricer.web;

import com.sbk.optionspricer.execution.TransportStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The dashboard says which transport is live and how the book reconciles with it. */
class TransportApiTest {

    @Test
    void thePaperTransportIsReportedWithNothingToReconcile() {
        Map<String, Object> json = OptionsDashboardServer.transportJson(TransportStatus.paper(25.0));

        assertEquals("paper", json.get("transport"));
        assertEquals("NOT_APPLICABLE", json.get("reconciliation"));
        assertNull(json.get("checkedAt"));
        assertTrue(json.get("description").toString().contains("25 bps"), json.toString());
        assertEquals(List.of(), json.get("differences"));
        assertFalse(Json.write(json).contains("NaN"));
    }

    @Test
    void theAlpacaTransportCarriesTheReconciliationAndItsDifferences() {
        Instant at = Instant.parse("2026-10-08T14:00:00Z");
        TransportStatus status = new TransportStatus("alpaca", "Alpaca paper account", "day limit orders at the touch", at, "MISMATCH",
                List.of("SPY: local 10, alpaca 0"));

        Map<String, Object> json = OptionsDashboardServer.transportJson(status);

        assertEquals("alpaca", json.get("transport"));
        assertEquals(at.toEpochMilli(), json.get("checkedAt"));
        assertEquals("MISMATCH", json.get("reconciliation"));
        assertEquals(List.of("SPY: local 10, alpaca 0"), json.get("differences"));
    }

    @Test
    void aMissingStatusIsNull() {
        assertNull(OptionsDashboardServer.transportJson(null));
    }
}
