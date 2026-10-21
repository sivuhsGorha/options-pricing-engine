package com.sbk.optionspricer;

import com.sbk.optionspricer.volatility.ChainReplay;
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
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The chain-replay command: a table, a plain verdict, the caveats, and a refusal when the data is not there. */
class MainChainReplayTest {

    private record Outcome(int exit, String text) {}

    private static Outcome run(Function<LocalDate, List<Close>> closes, ChainReplay.Pricer pricer) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Outcome(Main.runChainReplay(closes, pricer, 0.04), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static List<Close> closes(int n) {
        Random random = new Random(21);
        List<Close> list = new ArrayList<>();
        double price = 450.0;
        LocalDate d = LocalDate.of(2024, 1, 2);
        while (list.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                price *= Math.exp(0.007 * random.nextGaussian());
                list.add(new Close(d, price));
            }
            d = d.plusDays(1);
        }
        return list;
    }

    private static ChainReplay.Pricer market(List<Close> spy) {
        TreeMap<LocalDate, Double> spot = new TreeMap<>();
        spy.forEach(c -> spot.put(c.date(), c.close()));
        return (call, put, from, to) -> {
            var c = com.sbk.optionspricer.instruments.OccSymbol.parse(call).orElseThrow();
            TreeMap<LocalDate, Quotes> out = new TreeMap<>();
            spot.subMap(from, true, to, true).forEach((day, s) -> {
                if (day.isBefore(c.expiry())) {
                    double t = (c.expiry().toEpochDay() - day.toEpochDay()) / 365.0;
                    out.put(day, new Quotes(BlackScholesPricer.price(OptionType.CALL, s, c.strike(), t, 0.04, 0.16, 0.0),
                            BlackScholesPricer.price(OptionType.PUT, s, c.strike(), t, 0.04, 0.16, 0.0)));
                }
            });
            return out;
        };
    }

    @Test
    void itPrintsTheTablesTheVerdictAndTheCaveats() {
        List<Close> spy = closes(560);

        Outcome outcome = run(start -> spy, market(spy));

        assertEquals(0, outcome.exit(), outcome.text());
        assertTrue(outcome.text().contains("CHAIN REPLAY"), outcome.text());
        assertTrue(outcome.text().contains("Edge and the buyer's result") && outcome.text().contains("Lowest third") && outcome.text().contains("Highest third"), outcome.text());
        assertTrue(outcome.text().contains("The engine's rule"), outcome.text());
        assertTrue(outcome.text().contains("independent") && outcome.text().contains("not a test of the engine"), "the caveats are printed with the numbers: " + outcome.text());
    }

    @Test
    void withTooFewPricedDaysItStopsWithoutATable() {
        List<Close> spy = closes(560);

        Outcome outcome = run(start -> spy, (call, put, from, to) -> new TreeMap<>());

        assertEquals(2, outcome.exit());
        assertFalse(outcome.text().contains("Lowest third"), outcome.text());
        assertTrue(outcome.text().contains("never invents"), outcome.text());
    }

    @Test
    void anUnreachableDataSourceIsAnOperatorMessageNotAStackTrace() {
        Outcome outcome = run(start -> { throw new IllegalStateException("no network"); }, (call, put, from, to) -> new TreeMap<>());

        assertEquals(2, outcome.exit());
        assertFalse(outcome.text().contains("\tat "), outcome.text());
    }
}
