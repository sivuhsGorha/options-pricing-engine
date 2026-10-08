package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.execution.PositionTracker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The ledger is the record the book is rebuilt from: what was written is what comes back, and nothing else. */
class FillLedgerTest {

    private static Path temp() throws Exception {
        Path dir = Files.createTempDirectory("ledger");
        return dir.resolve("fills.csv");
    }

    @Test
    void fillsRoundTripThroughTheFileWithTheirPrice() throws Exception {
        FillLedger ledger = new FillLedger(temp());

        ledger.record("spy", 10, 1, 100.0);
        ledger.record("SPY261120C00780000", -2, 100, 9.9);

        List<FillLedger.Fill> fills = ledger.readAll();
        assertEquals(2, fills.size());
        assertEquals("SPY", fills.get(0).symbol(), "symbols are normalised");
        assertEquals(10, fills.get(0).quantity());
        assertEquals(100.0, fills.get(0).price(), 1e-9);
        assertEquals(-2, fills.get(1).quantity());
        assertEquals(100, fills.get(1).multiplier());
        assertEquals(9.9, fills.get(1).price(), 1e-9);
        assertEquals(3, Files.readAllLines(ledger.path()).size(), "header plus two rows");
    }

    @Test
    void aTrackerRestoredFromTheLedgerHasTheSamePositionsCostAndRealisedPnl() throws Exception {
        Path file = temp();
        PositionTracker live = new PositionTracker(new FillLedger(file));
        live.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 1, 100.0));
        live.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 1, 110.0));
        live.applyFill(new PositionTracker.ExecutionFill("SPY", -5, 1, 120.0));
        live.applyFill(new PositionTracker.ExecutionFill("SPY261120C00780000", -2, 100, 9.9));

        PositionTracker restored = PositionTracker.restore(new FillLedger(file));

        assertEquals(4, restored.replayedFills());
        assertEquals(15, restored.getNetQuantity("SPY"));
        assertEquals(105.0, restored.getAverageCost("SPY"), 1e-9);
        assertEquals(75.0, restored.getRealizedPnl(), 1e-9);
        assertEquals(-2, restored.getNetQuantity("SPY261120C00780000"));
        assertEquals(100, restored.getPosition("SPY261120C00780000").getMultiplier());
        assertEquals(5, Files.readAllLines(file).size(), "replaying does not append");

        restored.applyFill(new PositionTracker.ExecutionFill("SPY", 1, 1, 130.0));
        assertEquals(6, Files.readAllLines(file).size(), "new fills after a restore are recorded");
    }

    @Test
    void theRecordIsWrittenBeforeTheBookChangesSoAFailedWriteLeavesTheBookAlone() throws Exception {
        FillRecorder failing = (symbol, qty, multiplier, price) -> { throw new IllegalStateException("disk full"); };
        PositionTracker tracker = new PositionTracker(failing);

        assertThrows(IllegalStateException.class, () -> tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 1, 100.0)));
        assertEquals(0, tracker.getNetQuantity("SPY"), "nothing booked that was not recorded");
    }

    @Test
    void aCorruptRowStopsTheReplayNamingTheLine() throws Exception {
        Path file = temp();
        FillLedger ledger = new FillLedger(file);
        ledger.record("SPY", 10, 1, 100.0);
        Files.writeString(file, "2026-10-08T10:00:00Z,SPY,ten,1,100\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        IllegalStateException e = assertThrows(IllegalStateException.class, ledger::readAll);

        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ledger.record("SPY", 0, 1, 100.0), "a zero fill is not a fill");
    }
}
