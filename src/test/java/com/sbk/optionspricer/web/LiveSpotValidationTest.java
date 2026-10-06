package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LiveSpotValidationTest {

    @Test
    void sanitizeSymbolAcceptsStandardTickers() {
        assertEquals("SPY", LiveSpotProvider.sanitizeSymbol("SPY"));
        assertEquals("AAPL", LiveSpotProvider.sanitizeSymbol("aapl"));
        assertEquals("BRK.B", LiveSpotProvider.sanitizeSymbol("brk.b"));
        assertEquals("BF_B", LiveSpotProvider.sanitizeSymbol("bf_b"));
        assertEquals("SPY", LiveSpotProvider.sanitizeSymbol(null));
        assertEquals("SPY", LiveSpotProvider.sanitizeSymbol("  "));
    }

    @Test
    void sanitizeSymbolRejectsMaliciousOrInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> LiveSpotProvider.sanitizeSymbol("SPY/../../etc"));
        assertThrows(IllegalArgumentException.class, () -> LiveSpotProvider.sanitizeSymbol("SPY?cmd=id"));
        assertThrows(IllegalArgumentException.class, () -> LiveSpotProvider.sanitizeSymbol("SPY\r\nHost: evil.com"));
        assertThrows(IllegalArgumentException.class, () -> LiveSpotProvider.sanitizeSymbol("SPY;rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> LiveSpotProvider.sanitizeSymbol("A".repeat(17)));
    }
}
