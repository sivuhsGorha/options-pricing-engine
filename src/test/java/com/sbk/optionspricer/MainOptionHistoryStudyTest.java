package com.sbk.optionspricer;

import com.sbk.optionspricer.volatility.OptionHistoryStudy.Quotes;
import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MainOptionHistoryStudyTest {

    private record Outcome(int exit, String text) {}

    private static Outcome run(Function<LocalDate, List<Close>> closes, com.sbk.optionspricer.volatility.OptionHistoryStudy.Pricer pricer) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Outcome(Main.runOptionHistoryStudy(closes, pricer, 0.04), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static List<Close> weekdays(int n) {
        Random random = new Random(5);
        List<Close> list = new ArrayList<>();
        double price = 480.0;
        LocalDate d = LocalDate.of(2024, 1, 2);
        while (list.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                price *= Math.exp(0.008 * random.nextGaussian());
                list.add(new Close(d, price));
            }
            d = d.plusDays(1);
        }
        return list;
    }

    @Test
    void withoutRealClosesItNeverInventsThem() {
        Outcome o = run(start -> { throw new IllegalStateException("no keys"); }, (c, p, e) -> Optional.of(new Quotes(5, 5)));

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("never invents"), o.text());
    }

    @Test
    void withTooFewTradesItSaysHowManyItNeeds() {
        List<Close> data = weekdays(60);
        Outcome o = run(start -> data, (c, p, e) -> Optional.of(new Quotes(5, 5)));

        assertEquals(2, o.exit());
        assertTrue(o.text().contains("12"), o.text());
    }

    @Test
    void withEnoughDataItPrintsBothSchedulesBothSidesAndSaysWhatItIsNot() {
        List<Close> data = weekdays(700);
        Outcome o = run(start -> data, (c, p, e) -> Optional.of(new Quotes(5.0, 5.0)));

        assertEquals(0, o.exit(), o.text());
        for (String part : List.of("Monthly", "Weekly", "Buyer", "Seller", "By year", "mean", "Win rate")) {
            assertTrue(o.text().contains(part), part + " missing: " + o.text());
        }
        String lower = o.text().toLowerCase();
        for (String caveat : List.of("not a test of the engine", "no bid or ask", "assumed", "cannot write")) {
            assertTrue(lower.contains(caveat), caveat + " missing: " + o.text());
        }
    }
}
