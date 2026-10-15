package com.sbk.optionspricer;

import com.sbk.optionspricer.execution.DecisionJournal;
import com.sbk.optionspricer.execution.VolSpreadStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainSessionReportTest {

    private record Outcome(int exit, String text) {}

    private static Outcome report(Path dir, LocalDate day) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Outcome(Main.runSessionReport(dir, day), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static void decision(DecisionJournal journal, String at, String action, String reason) {
        journal.record(new VolSpreadStrategy.Decision(Instant.parse(at), action, reason, LocalDate.of(2026, 11, 13), 769.0,
                0.14, 0.14, 0.0, 0, List.of()));
    }

    @Test
    void withoutAJournalItSaysWhyInsteadOfPrintingAnEmptyReport(@TempDir Path dir) {
        Outcome o = report(dir, null);

        assertEquals(1, o.exit());
        assertTrue(o.text().contains("decisions.csv") && o.text().contains("journal"), o.text());
        assertFalse(o.text().contains("SESSION REPORT"), o.text());
    }

    @Test
    void withNoDayGivenItReportsTheLatestDayInTheJournal(@TempDir Path dir) {
        DecisionJournal journal = new DecisionJournal(dir.resolve("decisions.csv"));
        decision(journal, "2026-10-14T14:00:00Z", "HOLD", "x");
        decision(journal, "2026-10-15T14:00:00Z", "HOLD", "y");
        decision(journal, "2026-10-15T14:00:30Z", "HOLD", "y");

        Outcome o = report(dir, null);

        assertEquals(0, o.exit(), o.text());
        assertTrue(o.text().contains("SESSION REPORT 2026-10-15"), o.text());
        assertTrue(o.text().contains("2 decisions"), o.text());
    }

    @Test
    void aDayWithNothingRecordedIsReportedAsSuchAndNotAsAQuietOne(@TempDir Path dir) {
        DecisionJournal journal = new DecisionJournal(dir.resolve("decisions.csv"));
        decision(journal, "2026-10-15T14:00:00Z", "HOLD", "y");

        Outcome o = report(dir, LocalDate.of(2026, 10, 12));

        assertEquals(0, o.exit(), o.text());
        assertTrue(o.text().contains("no decisions recorded"), o.text());
    }
}
