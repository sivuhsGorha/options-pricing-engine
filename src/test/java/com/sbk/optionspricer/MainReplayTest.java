package com.sbk.optionspricer;

import com.sbk.optionspricer.data.CompressedChainSnapshotRecorder;
import com.sbk.optionspricer.volatility.DailyCloseFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MainReplayTest {

    private static final Instant T0 = Instant.parse("2026-10-14T14:30:00Z");

    /** Runs the replay and returns its exit code and everything it printed (to stdout and stderr). */
    private static record Outcome(int exit, String text) {}

    private static Outcome replay(Path dir, java.util.function.Function<LocalDate, List<DailyCloseFeed.DailyClose>> closes) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            int exit = Main.runReplay(dir, closes);
            return new Outcome(exit, buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static void record(Path dir, int minute) throws Exception {
        LocalDate near = LocalDate.of(2026, 11, 20);
        LocalDate far = LocalDate.of(2027, 1, 15);
        List<OptionChain> chains = List.of(chain(near, 14.0), chain(far, 30.0));
        try (CompressedChainSnapshotRecorder recorder = new CompressedChainSnapshotRecorder(dir, Clock.fixed(T0, ZoneOffset.UTC))) {
            recorder.record(chains, "CBOE_DELAYED", T0.plusSeconds(60L * minute));
            recorder.flush(5, TimeUnit.SECONDS);
        }
    }

    private static OptionChain chain(LocalDate expiry, double callPrice) {
        return new OptionChain("SPY", expiry, 500.0, List.of(
                new OptionQuote("SPY", expiry, 500.0, OptionType.CALL, callPrice - 0.1, callPrice + 0.1, 0.26, 100, 1000),
                new OptionQuote("SPY", expiry, 500.0, OptionType.PUT, callPrice - 0.5, callPrice - 0.3, 0.26, 100, 1000)));
    }

    private static List<DailyCloseFeed.DailyClose> closes() {
        List<DailyCloseFeed.DailyClose> out = new ArrayList<>();
        double price = 500.0;
        for (int k = 40; k >= 1; k--) {
            price *= 1.0 + (k % 2 == 0 ? 0.01 : -0.01);
            out.add(new DailyCloseFeed.DailyClose(LocalDate.of(2026, 10, 14).minusDays(k), price));
        }
        return out;
    }

    @Test
    void withoutRealDailyClosesTheReplayStopsInsteadOfInventingSome(@TempDir Path dir) throws Exception {
        record(dir, 0);

        Outcome o = replay(dir, start -> { throw new IllegalStateException("no ALPACA keys"); });

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("daily closes"), o.text());
        assertTrue(o.text().contains("no ALPACA keys"), o.text());
        assertFalse(o.text().contains("Total P&L"), "no results table without a real forecast input:\n" + o.text());
    }

    @Test
    void anEmptyRecordingSaysSoAndFails(@TempDir Path dir) {
        Outcome o = replay(dir, start -> closes());

        assertEquals(1, o.exit());
        assertTrue(o.text().contains("No snapshot files"), o.text());
    }

    @Test
    void theReportCountsMomentsAndDaysNotChainsAndSaysWhenTheSampleIsTooSmallToConcludeAnything(@TempDir Path dir) throws Exception {
        record(dir, 0);
        record(dir, 1);
        record(dir, 2);

        Outcome o = replay(dir, start -> closes());

        assertEquals(0, o.exit(), o.text());
        assertTrue(o.text().contains("3 moments") && o.text().contains("6 chains") && o.text().contains("1 trading day"), o.text());
        assertTrue(o.text().toLowerCase().contains("too few"), "a day of data must not be presented as evidence:\n" + o.text());
        assertFalse(o.text().contains("Quantitative Synthesis"), o.text());
        assertTrue(o.text().contains("Total P&L"), o.text());
    }
}
