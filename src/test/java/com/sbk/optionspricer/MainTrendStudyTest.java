package com.sbk.optionspricer;

import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The trend-study command: both columns, the halves, the neighbouring windows, the rule applied without discretion, and the caveats. */
class MainTrendStudyTest {

    private record Outcome(int exit, String text) {}

    private static Outcome run(Function<LocalDate, List<Close>> closes) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Outcome(Main.runTrendStudy(closes, 200, 5.0), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static List<Close> closes(int n, long seed) {
        Random random = new Random(seed);
        List<Close> list = new ArrayList<>();
        double price = 200.0;
        LocalDate d = LocalDate.of(2015, 1, 5);
        while (list.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                price *= 1 + 0.0004 + 0.01 * random.nextGaussian();
                list.add(new Close(d, price));
            }
            d = d.plusDays(1);
        }
        return list;
    }

    @Test
    void itPrintsTheComparisonTheRuleTheVerdictAndTheCaveats() {
        Outcome outcome = run(start -> closes(2700, 9));

        assertEquals(0, outcome.exit(), outcome.text());
        assertTrue(outcome.text().contains("TREND-FOLLOWING") && outcome.text().contains("Trend rule") && outcome.text().contains("Buy and hold"), outcome.text());
        assertTrue(outcome.text().contains("Largest fall from a peak") && outcome.text().contains("Sharpe"), outcome.text());
        assertTrue(outcome.text().contains("Pass rule") && outcome.text().matches("(?s).*(PASS|UNPROVEN|FAIL).*"), outcome.text());
        assertTrue(outcome.text().contains("not a trade signal") && outcome.text().contains("false-positive"), "the caveats are printed with the numbers: " + outcome.text());
    }

    @Test
    void tooFewClosesOrAnUnreachableSourceStopsWithAnOperatorMessage() {
        Outcome few = run(start -> closes(150, 1));
        assertEquals(2, few.exit());
        assertFalse(few.text().contains("Pass rule"), few.text());

        Outcome down = run(start -> { throw new IllegalStateException("no network"); });
        assertEquals(2, down.exit());
        assertFalse(down.text().contains("\tat "), down.text());
        assertTrue(down.text().contains("never invents"), down.text());
    }
}
