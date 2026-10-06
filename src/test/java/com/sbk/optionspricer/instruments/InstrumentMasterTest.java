package com.sbk.optionspricer.instruments;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InstrumentMasterTest {
    @Test
    void updatesFromOptionChainAndMarksExpiredContracts() {
        InstrumentMaster master = new InstrumentMaster();
        LocalDate expiry = LocalDate.now().plusDays(30);
        List<OptionQuote> quotes = List.of(
                new OptionQuote("SPY", expiry, 100.0, OptionType.CALL, 5.0, 5.5, 0.20, 100, 500),
                new OptionQuote("SPY", expiry, 110.0, OptionType.PUT, 8.0, 8.5, 0.22, 80, 400)
        );

        master.updateFromChain(new OptionChain("SPY", expiry, 100.0, quotes));
        assertEquals(2, master.size());
        assertTrue(master.get("SPY", expiry, 100.0, OptionType.CALL).isPresent());

        LocalDate expired = LocalDate.now().minusDays(1);
        master.upsert(new Instrument("SPY", expired, 95.0, OptionType.CALL, 100.0, "09:30-16:00", 0.01, true));
        master.markExpiredInactive(LocalDate.now());

        Instrument expiredInstrument = master.get("SPY", expired, 95.0, OptionType.CALL).orElseThrow();
        assertFalse(expiredInstrument.active(), "expired instrument should be marked inactive");
    }
}
