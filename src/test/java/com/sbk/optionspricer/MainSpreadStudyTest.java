package com.sbk.optionspricer;

import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.volatility.SpreadHistoryStudy;
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
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The spread-study command: the tables, the pass rule applied without discretion, the caveats, and a refusal when the data is not there. */
class MainSpreadStudyTest {

    private record Outcome(int exit, String text) {}

    private static Outcome run(Function<LocalDate, List<Close>> closes, SpreadHistoryStudy.Pricer pricer) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Outcome(Main.runSpreadStudy(closes, pricer, 0.05, 0.03, 5.0), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static List<Close> closes(int n) {
        Random random = new Random(33);
        List<Close> list = new ArrayList<>();
        double price = 450.0;
        LocalDate d = LocalDate.of(2024, 1, 2);
        while (list.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                price *= Math.exp(0.006 * random.nextGaussian());
                list.add(new Close(d, price));
            }
            d = d.plusDays(1);
        }
        return list;
    }

    private static SpreadHistoryStudy.Pricer market(List<Close> spy, double vol) {
        TreeMap<LocalDate, Double> spot = new TreeMap<>();
        spy.forEach(c -> spot.put(c.date(), c.close()));
        return (symbols, entry) -> {
            double s = spot.floorEntry(entry).getValue();
            double[] prices = new double[symbols.size()];
            for (int i = 0; i < prices.length; i++) {
                var o = OccSymbol.parse(symbols.get(i)).orElseThrow();
                double t = (o.expiry().toEpochDay() - entry.toEpochDay()) / 365.0;
                prices[i] = BlackScholesPricer.price(o.type(), s, o.strike(), t, 0.04, vol, 0.0);
            }
            return Optional.of(prices);
        };
    }

    @Test
    void itPrintsBothSpreadsTheYearsTheVerdictAndTheCaveats() {
        List<Close> spy = closes(640);

        Outcome outcome = run(start -> spy, market(spy, 0.20));

        assertEquals(0, outcome.exit(), outcome.text());
        assertTrue(outcome.text().contains("SPREAD HISTORY") && outcome.text().contains("Put credit spread") && outcome.text().contains("Iron condor"), outcome.text());
        assertTrue(outcome.text().contains("Pass rule") && outcome.text().matches("(?s).*(PASS|UNPROVEN|FAIL).*"), outcome.text());
        assertTrue(outcome.text().contains("By year"), outcome.text());
        assertTrue(outcome.text().contains("not a trade signal") && outcome.text().contains("daily close has no bid or ask"), "the caveats are printed with the numbers: " + outcome.text());
    }

    @Test
    void withTooFewPricedTradesItStopsWithoutATable() {
        List<Close> spy = closes(640);

        Outcome outcome = run(start -> spy, (symbols, entry) -> Optional.empty());

        assertEquals(2, outcome.exit());
        assertFalse(outcome.text().contains("Pass rule"), outcome.text());
        assertTrue(outcome.text().contains("never invents"), outcome.text());
    }

    @Test
    void anUnreachableDataSourceIsAnOperatorMessageNotAStackTrace() {
        Outcome outcome = run(start -> { throw new IllegalStateException("no network"); }, (symbols, entry) -> Optional.empty());

        assertEquals(2, outcome.exit());
        assertFalse(outcome.text().contains("\tat "), outcome.text());
    }
}
