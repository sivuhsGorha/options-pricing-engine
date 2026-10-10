package com.sbk.optionspricer.data;

import com.sbk.optionspricer.OptionType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HistoricalDataManagerTest {
    @Test
    void storesAndReadsSnapshots() throws Exception {
        Path tempDir = Files.createTempDirectory("historical-data");
        HistoricalDataManager manager = new HistoricalDataManager(tempDir);
        OptionSnapshot snapshot = new OptionSnapshot(
                Instant.now(),
                "SPY",
                LocalDate.now().plusDays(30),
                100.0,
                OptionType.CALL,
                100.0,
                5.0,
                5.5,
                0.20,
                50,
                200
        );

        manager.store(snapshot);
        List<OptionSnapshot> loaded = manager.query("SPY");

        assertEquals(1, loaded.size());
        assertEquals(snapshot.symbol(), loaded.get(0).symbol());
        assertEquals(snapshot.strike(), loaded.get(0).strike(), 1e-9);
        assertEquals(snapshot.midPrice(), loaded.get(0).midPrice(), 1e-9);
    }
}
