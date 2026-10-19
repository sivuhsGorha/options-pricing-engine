package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionJournalTest {

    private static final Instant T0 = Instant.parse("2026-10-15T14:00:00Z");

    private static VolSpreadStrategy.Decision hold(int seconds, String reason) {
        return new VolSpreadStrategy.Decision(T0.plusSeconds(seconds), "HOLD", reason, LocalDate.of(2026, 11, 13), 769.0,
                0.140, 0.143, -0.003, 0, List.of());
    }

    private static VolSpreadStrategy.Decision wait(int seconds, String reason) {
        return new VolSpreadStrategy.Decision(T0.plusSeconds(seconds), "WAIT", reason, null, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, 0, List.of());
    }

    @Test
    void appendsOneRowPerDecisionWithTheHeaderOnceEvenAcrossRestarts(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("decisions.csv");
        new DecisionJournal(file).record(hold(0, "edge within the 1.00-point band"));
        DecisionJournal afterRestart = new DecisionJournal(file); // a new process opens the same file
        afterRestart.record(hold(30, "edge within the 1.00-point band"));
        afterRestart.record(wait(60, "spot quote STALE"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.stream().filter(l -> l.startsWith("at,action,")).count(), "one header");
        List<DecisionJournal.Row> rows = DecisionJournal.read(file);
        assertEquals(3, rows.size());
        assertEquals(T0, rows.get(0).at());
        assertEquals("HOLD", rows.get(1).action());
        assertEquals("WAIT", rows.get(2).action());
    }

    @Test
    void aReasonWithCommasQuotesAndLineBreaksComesBackExactly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("decisions.csv");
        String reason = "edge within the 1.00-point band | RV forecast 13.7%, VRP +0.3 pts, \"quoted\"\nsecond line";
        VolSpreadStrategy.Decision d = new VolSpreadStrategy.Decision(T0, "BUY_STRADDLE_REJECTED", reason, LocalDate.of(2026, 11, 13),
                769.0, 0.140, 0.150, -0.010, 0, List.of("BUY 1 SPY261113C00769000 rejected: x, y", "second"));
        new DecisionJournal(file).record(d);

        DecisionJournal.Row row = DecisionJournal.read(file).get(0);

        assertEquals(reason, row.reason());
        assertEquals("BUY 1 SPY261113C00769000 rejected: x, y; second", row.orders());
        assertEquals(0.140, row.marketIv(), 1e-9);
        assertEquals(-0.010, row.edge(), 1e-9);
        assertEquals("2026-11-13", row.expiry());
        assertEquals(769.0, row.strike(), 1e-9);
    }

    @Test
    void aDecisionWithNoNumbersLeavesThemBlankAndReadsBackAsNotANumber(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("decisions.csv");
        new DecisionJournal(file).record(wait(0, "no loaded expiry at least 14 days out"));

        DecisionJournal.Row row = DecisionJournal.read(file).get(0);

        assertTrue(Double.isNaN(row.marketIv()) && Double.isNaN(row.edge()) && Double.isNaN(row.strike()));
        assertEquals("", row.expiry());
        assertFalse(Files.readString(file).contains("NaN"), "blank, not the word NaN");
    }

    @Test
    void aJournalThatCannotBeWrittenNeverStopsTheStrategy(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("a-file");
        Files.writeString(blocker, "not a directory");
        DecisionJournal journal = new DecisionJournal(blocker.resolve("decisions.csv")); // its parent is a file

        assertDoesNotThrow(() -> journal.record(hold(0, "x")));
        assertDoesNotThrow(() -> journal.record(hold(30, "y")));
        assertTrue(journal.failures() >= 1);
    }

    @Test
    void readingAFileThatIsNotThereGivesNoRows(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), DecisionJournal.read(dir.resolve("nope.csv")));
    }

    @Test
    void recordsAgeTelemetryFieldsAndRoundTripsThem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("decisions.csv");
        VolSpreadStrategy.Decision d = new VolSpreadStrategy.Decision(T0, "HOLD", "within band", LocalDate.of(2026, 11, 13),
                769.0, 0.140, 0.143, -0.003, 0, List.of(), 1.2, 45.0, 3.5);
        new DecisionJournal(file).record(d);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(lines.get(0).contains(",spotAgeSeconds,chainAgeSeconds,optionQuoteAgeSeconds"), "header has age fields: " + lines.get(0));
        assertTrue(lines.get(1).contains(",1.2,45.0,3.5,"), "row carries the formatted ages: " + lines.get(1));

        DecisionJournal.Row row = DecisionJournal.read(file).get(0);
        assertEquals(1.2, row.spotAgeSeconds(), 1e-6);
        assertEquals(45.0, row.chainAgeSeconds(), 1e-6);
        assertEquals(3.5, row.optionQuoteAgeSeconds(), 1e-6);
    }

    @Test
    void readsLegacyTenColumnRowsWithNaNForAges(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("legacy_decisions.csv");
        String legacyCsv = "at,action,expiry,strike,marketIv,referenceIv,edge,straddleSign,orders,reason\n"
                + "2026-10-15T14:00:00Z,HOLD,2026-11-13,769.00,0.140000,0.143000,-0.003000,0,,within band\n";
        Files.writeString(file, legacyCsv, StandardCharsets.UTF_8);

        List<DecisionJournal.Row> rows = DecisionJournal.read(file);
        assertEquals(1, rows.size());
        DecisionJournal.Row r = rows.get(0);
        assertEquals("HOLD", r.action());
        assertTrue(Double.isNaN(r.spotAgeSeconds()), "legacy spotAge is NaN");
        assertTrue(Double.isNaN(r.chainAgeSeconds()), "legacy chainAge is NaN");
        assertTrue(Double.isNaN(r.optionQuoteAgeSeconds()), "legacy optionQuoteAge is NaN");
    }

    @Test
    void recordsTheOptionSpreadAndReadsOlderRowsWithoutIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("decisions.csv");
        new DecisionJournal(file).record(new VolSpreadStrategy.Decision(T0, "HOLD", "within band", LocalDate.of(2026, 11, 13),
                769.0, 0.140, 0.143, -0.003, 0, List.of(), 1.2, 45.0, 3.5, 187.4));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(lines.get(0).endsWith(",optionQuoteAgeSeconds,optionSpreadBps"), lines.get(0));
        assertTrue(lines.get(1).endsWith(",1.2,45.0,3.5,187.4"), lines.get(1));
        assertEquals(187.4, DecisionJournal.read(file).get(0).optionSpreadBps(), 1e-6);

        // a row written before the column existed (13 fields) has no spread
        Path older = dir.resolve("older.csv");
        Files.writeString(older, "at,action,expiry,strike,marketIv,referenceIv,edge,straddleSign,orders,reason,spotAgeSeconds,chainAgeSeconds,optionQuoteAgeSeconds\n"
                + "2026-10-15T14:00:00Z,HOLD,2026-11-13,769.00,0.140000,0.143000,-0.003000,0,,within band,1.2,45.0,3.5\n", StandardCharsets.UTF_8);
        DecisionJournal.Row row = DecisionJournal.read(older).get(0);
        assertEquals(3.5, row.optionQuoteAgeSeconds(), 1e-6);
        assertTrue(Double.isNaN(row.optionSpreadBps()));
    }
}
