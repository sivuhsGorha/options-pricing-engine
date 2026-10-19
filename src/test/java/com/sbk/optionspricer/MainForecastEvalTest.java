package com.sbk.optionspricer;

import com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MainForecastEvalTest {

    private record Outcome(int exit, String text) {}

    private static Outcome run(java.util.function.Function<LocalDate, List<DailyClose>> source) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Outcome(Main.runForecastEval(source), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static List<DailyClose> closes(int n) {
        Random random = new Random(11);
        List<DailyClose> list = new ArrayList<>();
        double price = 500.0;
        LocalDate day = LocalDate.of(2026, 1, 2);
        while (list.size() < n) {
            if (day.getDayOfWeek().getValue() < 6) {
                price *= Math.exp(0.01 * random.nextGaussian());
                list.add(new DailyClose(day, price));
            }
            day = day.plusDays(1);
        }
        return list;
    }

    @Test
    void withoutRealClosesItStopsAndSaysItNeverInventsThem() {
        Outcome o = run(start -> { throw new IllegalStateException("no keys"); });

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("never invents"), o.text());
        assertFalse(o.text().contains("EWMA"), "no table without data: " + o.text());
    }

    @Test
    void withTooFewClosesItSaysHowManyItNeeds() {
        Outcome o = run(start -> closes(80));

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("80"), o.text());
    }

    @Test
    void withEnoughClosesItPrintsEveryModelForBothHorizonsAndStatesTheLimits() {
        Outcome o = run(start -> closes(300));

        assertEquals(0, o.exit(), o.text());
        for (String model : List.of("trailing 22d", "EWMA", "HAR default", "HAR fitted")) {
            assertTrue(o.text().contains(model), model + " missing: " + o.text());
        }
        assertTrue(o.text().contains("5 days") && o.text().contains("21 days"), o.text());
        assertTrue(o.text().contains("overlap"), "must say the windows overlap: " + o.text());
    }
}
