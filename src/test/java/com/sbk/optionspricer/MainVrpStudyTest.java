package com.sbk.optionspricer;

import com.sbk.optionspricer.execution.AlpacaPaperClient;
import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MainVrpStudyTest {

    private record Outcome(int exit, String text) {}

    private static Outcome run(Path vix, Function<LocalDate, List<Close>> closes) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Outcome(Main.runVrpStudy(vix, closes), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    /** Weekday closes with a random walk, and a VIX file for the same days at a fixed level. */
    private static List<Close> closes(int n) {
        Random random = new Random(3);
        List<Close> list = new ArrayList<>();
        double price = 300.0;
        LocalDate d = LocalDate.of(2016, 1, 4);
        while (list.size() < n) {
            if (d.getDayOfWeek().getValue() < 6) {
                price *= Math.exp(0.009 * random.nextGaussian());
                list.add(new Close(d, price));
            }
            d = d.plusDays(1);
        }
        return list;
    }

    private static Path vixFile(Path dir, List<Close> closes, double level) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("DATE,OPEN,HIGH,LOW,CLOSE");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        for (Close c : closes) lines.add(c.date().format(f) + "," + level + "," + level + "," + level + "," + level);
        Path file = dir.resolve("VIX_History.csv");
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void withoutAVixFileItSaysWhereToGetOneAndStops(@TempDir Path dir) {
        Outcome o = run(dir.resolve("missing.csv"), start -> closes(600));

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("VIX") && o.text().contains("cdn.cboe.com"), o.text());
    }

    @Test
    void withoutRealClosesItNeverInventsThem(@TempDir Path dir) throws IOException {
        List<Close> data = closes(600);
        Outcome o = run(vixFile(dir, data, 18.0), start -> { throw new IllegalStateException("no keys"); });

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("never invents"), o.text());
    }

    @Test
    void withTooFewDaysOfBothItSaysHowManyItNeeds(@TempDir Path dir) throws IOException {
        List<Close> data = closes(100);
        Outcome o = run(vixFile(dir, data, 18.0), start -> data);

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("250"), o.text());
    }

    @Test
    void withEnoughDataItPrintsTheStudyAndSaysWhatItIsNot(@TempDir Path dir) throws IOException {
        List<Close> data = closes(900);
        Outcome o = run(vixFile(dir, data, 18.0), start -> data);

        assertEquals(0, o.exit(), o.text());
        for (String part : List.of("premium", "By year", "VIX under 15", "VIX 15 to 20", "Non-overlapping", "Worst")) {
            assertTrue(o.text().contains(part), part + " missing: " + o.text());
        }
        for (String caveat : List.of("not a backtest", "overlap", "no costs")) {
            assertTrue(o.text().toLowerCase().contains(caveat), caveat + " missing: " + o.text());
        }
    }

    @Test
    void theBarsAreFetchedPageByPageUntilThereAreNoMoreAndNeverTwice() {
        List<AlpacaPaperClient.DailyBar> all = new ArrayList<>();
        LocalDate d = LocalDate.of(2016, 1, 1);
        for (int i = 0; i < 2500; i++) all.add(new AlpacaPaperClient.DailyBar(d.plusDays(i), 100.0 + i));
        List<LocalDate> starts = new ArrayList<>();

        List<AlpacaPaperClient.DailyBar> got = AlpacaPaperClient.pageForward(start -> {
            starts.add(start);
            return all.stream().filter(b -> !b.date().isBefore(start)).limit(1000).toList();
        }, LocalDate.of(2016, 1, 1));

        assertEquals(2500, got.size());
        assertEquals(all.get(0).date(), got.get(0).date());
        assertEquals(all.get(2499).date(), got.get(2499).date());
        assertEquals(got.size(), got.stream().map(AlpacaPaperClient.DailyBar::date).distinct().count(), "no date twice");
        assertEquals(3, starts.size(), "1000 + 1000 + 500: the short page ends it");
    }
}
