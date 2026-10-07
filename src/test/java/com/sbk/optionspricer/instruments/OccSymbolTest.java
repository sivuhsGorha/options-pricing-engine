package com.sbk.optionspricer.instruments;

import com.sbk.optionspricer.OptionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class OccSymbolTest {

    @Test
    void formatsAndParsesTheOccContractSymbol() {
        LocalDate expiry = LocalDate.of(2026, 11, 20);
        assertEquals("SPY261120C00780000", OccSymbol.format("SPY", expiry, OptionType.CALL, 780.0));
        assertEquals("SPY261120P00782500", OccSymbol.format("spy", expiry, OptionType.PUT, 782.5), "root is upper-cased, half strikes keep their thousandths");

        OccSymbol.Parsed parsed = OccSymbol.parse("SPY261120C00780000").orElseThrow();
        assertEquals("SPY", parsed.underlying());
        assertEquals(expiry, parsed.expiry());
        assertEquals(OptionType.CALL, parsed.type());
        assertEquals(780.0, parsed.strike());

        Instrument instrument = new Instrument("SPY", expiry, 780.0, OptionType.CALL, 100.0, null, 0.01, true);
        assertEquals("SPY261120C00780000", instrument.contractSymbol());
    }

    @Test
    void aPlainTickerAndMalformedSymbolsAreNotContracts() {
        assertTrue(OccSymbol.parse("SPY").isEmpty());
        assertTrue(OccSymbol.parse("SPY261120X00780000").isEmpty());
        assertTrue(OccSymbol.parse("SPY261345C00780000").isEmpty(), "month 13 is not a date");
        assertTrue(OccSymbol.parse("SPY261120C00000000").isEmpty(), "a zero strike is not a contract");
        assertTrue(OccSymbol.parse(null).isEmpty());
    }

    @Test
    void formatRejectsWhatItCannotEncode() {
        LocalDate expiry = LocalDate.of(2026, 11, 20);
        assertThrows(IllegalArgumentException.class, () -> OccSymbol.format("TOOLONGROOT", expiry, OptionType.CALL, 100));
        assertThrows(IllegalArgumentException.class, () -> OccSymbol.format("SPY", expiry, OptionType.CALL, -5));
        assertThrows(IllegalArgumentException.class, () -> OccSymbol.format("SPY", expiry, OptionType.CALL, 1e9));
    }
}
