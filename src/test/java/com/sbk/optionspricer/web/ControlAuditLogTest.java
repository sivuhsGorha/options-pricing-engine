package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Who changed what on the running engine, and when: every halt, resume, strategy switch and test order, and every attempt that was refused. */
class ControlAuditLogTest {

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void eachActionIsOneCsvRowWithTimeActionOutcomeClientAndDetail() throws IOException {
        Path file = dir.resolve("control_audit.csv");
        ControlAuditLog log = new ControlAuditLog(file, clock);

        log.record("halt", "applied", "127.0.0.1", "operator: manual check");
        log.record("resume", "applied", "127.0.0.1", "");

        List<String> lines = Files.readAllLines(file);
        assertEquals("timestamp,action,outcome,client,detail", lines.get(0));
        assertEquals("2026-10-21T10:15:30Z,halt,applied,127.0.0.1,operator: manual check", lines.get(1));
        assertEquals("2026-10-21T10:15:30Z,resume,applied,127.0.0.1,", lines.get(2));
        assertEquals(3, lines.size());
    }

    @Test
    void aDetailWithCommasQuotesOrNewlinesCannotBreakTheRowOrForgeAnother() throws IOException {
        Path file = dir.resolve("control_audit.csv");
        ControlAuditLog log = new ControlAuditLog(file, clock);

        log.record("halt", "applied", "10.0.0.5", "a,b \"quoted\"\n2026-10-21T00:00:00Z,resume,applied,evil,forged");

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "one row, however the text is written: " + lines);
        assertTrue(lines.get(1).contains("\"a,b \"\"quoted\"\""), lines.get(1));
    }

    @Test
    void aLongDetailIsCutAndTheLogAppendsAcrossRestarts() throws IOException {
        Path file = dir.resolve("control_audit.csv");
        new ControlAuditLog(file, clock).record("halt", "applied", "c", "x".repeat(5_000));
        new ControlAuditLog(file, clock).record("resume", "applied", "c", "");

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), "the header is written once");
        assertTrue(lines.get(1).length() < 400, "length " + lines.get(1).length());
    }

    @Test
    void anUnwritableLogNeverBreaksTheControlThatIsBeingAudited() {
        ControlAuditLog log = new ControlAuditLog(dir, clock); // a directory cannot be opened as a file

        assertDoesNotThrow(() -> log.record("halt", "applied", "c", "d"));
    }
}
