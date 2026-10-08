package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The P&L record is sampled, survives a restart, and the day's P&L and drawdown are measured in New York time. */
class PnlHistoryTest {

    private static Path temp() throws Exception {
        return Files.createTempDirectory("pnl-history").resolve("pnl_history.csv");
    }

    private static Valuation valuation(String at, double realized, double unrealized) {
        return new Valuation(Instant.parse(at), 777.0, "TEST/LIVE", List.of(), unrealized, realized, 0, 0, 0, 0, 0, List.of());
    }

    @Test
    void samplesAreThrottledUnlessRealisedPnlChanges() throws Exception {
        PnlHistory history = new PnlHistory(temp(), Duration.ofSeconds(60));

        assertTrue(history.record(valuation("2026-10-08T14:00:00Z", 0.0, 10.0)), "the first valuation is always recorded");
        assertFalse(history.record(valuation("2026-10-08T14:00:05Z", 0.0, 12.0)), "five seconds later, nothing new to record");
        assertTrue(history.record(valuation("2026-10-08T14:00:10Z", 75.0, 12.0)), "a fill that realised P&L is recorded at once");
        assertFalse(history.record(valuation("2026-10-08T14:00:50Z", 75.0, 20.0)));
        assertTrue(history.record(valuation("2026-10-08T14:01:10Z", 75.0, 20.0)), "a minute after the last row");
        assertFalse(history.record(valuation("2026-10-08T13:00:00Z", 75.0, 20.0)), "a valuation older than the last row is not appended");
        assertFalse(history.record(new Valuation(Instant.parse("2026-10-08T14:05:00Z"), 777.0, "TEST", List.of(), Double.NaN, 75.0, 0, 0, 0, 0, 0, List.of())),
                "an unmarkable book is not a sample");

        assertEquals(3, history.size());
        assertEquals(4, Files.readAllLines(history.path()).size(), "header plus three rows");
    }

    @Test
    void todaysPnlIsMeasuredFromYesterdaysLastMarkInNewYorkTimeAndDrawdownIsPeakToTrough() throws Exception {
        PnlHistory history = new PnlHistory(temp(), Duration.ZERO);
        history.record(valuation("2026-10-07T20:00:00Z", 100.0, 0.0));   // 16:00 ET on the 7th: total 100
        history.record(valuation("2026-10-08T01:00:00Z", 100.0, 10.0));  // 21:00 ET on the 7th, still yesterday: total 110
        history.record(valuation("2026-10-08T13:35:00Z", 100.0, 20.0));  // 09:35 ET on the 8th: 120
        history.record(valuation("2026-10-08T14:00:00Z", 100.0, -10.0)); // 90
        history.record(valuation("2026-10-08T15:00:00Z", 130.0, 0.0));   // 130

        PnlHistory.Summary s = history.summary(Instant.parse("2026-10-08T15:30:00Z")).orElseThrow();

        assertEquals(LocalDate.of(2026, 10, 8), s.day());
        assertEquals(Instant.parse("2026-10-08T04:00:00Z"), s.dayStart(), "midnight in New York (EDT)");
        assertTrue(s.baselineIsPreviousClose());
        assertEquals(110.0, s.dayBaseline(), 1e-9, "the 21:00 ET sample is yesterday's last mark, not today's first");
        assertEquals(130.0, s.total(), 1e-9);
        assertEquals(130.0, s.realized(), 1e-9);
        assertEquals(0.0, s.unrealized(), 1e-9);
        assertEquals(20.0, s.dayPnl(), 1e-9);
        assertEquals(130.0, s.dayPeak(), 1e-9);
        assertEquals(90.0, s.dayTrough(), 1e-9);
        assertEquals(30.0, s.dayMaxDrawdown(), 1e-9, "120 down to 90");
        assertEquals(3, s.today().size());
        assertEquals(Instant.parse("2026-10-07T20:00:00Z"), s.firstSampleAt());
        assertEquals(Instant.parse("2026-10-08T15:00:00Z"), s.asOf());
    }

    @Test
    void withoutAPreviousDayTheFirstSampleOfTheDayIsTheBaseline() throws Exception {
        PnlHistory history = new PnlHistory(temp(), Duration.ZERO);
        assertTrue(history.summary(Instant.parse("2026-10-08T15:30:00Z")).isEmpty(), "nothing sampled yet");
        history.record(valuation("2026-10-08T13:35:00Z", 0.0, 50.0));
        history.record(valuation("2026-10-08T14:00:00Z", 0.0, 35.0));

        PnlHistory.Summary s = history.summary(Instant.parse("2026-10-08T15:30:00Z")).orElseThrow();

        assertFalse(s.baselineIsPreviousClose());
        assertEquals(50.0, s.dayBaseline(), 1e-9);
        assertEquals(-15.0, s.dayPnl(), 1e-9);
        assertEquals(15.0, s.dayMaxDrawdown(), 1e-9);
    }

    @Test
    void theHistorySurvivesARestart() throws Exception {
        Path file = temp();
        PnlHistory first = new PnlHistory(file, Duration.ZERO);
        first.record(valuation("2026-10-08T13:35:00Z", 5.0, 50.0));
        first.record(valuation("2026-10-08T14:00:00Z", 5.0, 35.0));

        PnlHistory reopened = new PnlHistory(file, Duration.ZERO);

        assertEquals(2, reopened.size());
        PnlHistory.Summary s = reopened.summary(Instant.parse("2026-10-08T15:30:00Z")).orElseThrow();
        assertEquals(40.0, s.total(), 1e-9);
        assertEquals(-15.0, s.dayPnl(), 1e-9);
        assertEquals(Instant.parse("2026-10-08T13:35:00Z"), s.firstSampleAt());
    }

    @Test
    void aCorruptRowStopsTheLoadNamingTheLine() throws Exception {
        Path file = temp();
        new PnlHistory(file, Duration.ZERO).record(valuation("2026-10-08T13:35:00Z", 5.0, 50.0));
        Files.writeString(file, "2026-10-08T14:00:00Z,777,five,50\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PnlHistory(file, Duration.ZERO));

        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }
}
