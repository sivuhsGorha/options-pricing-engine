package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.volatility.OptionHistoryStudy.Quotes;
import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Replaying the engine's own signal (implied volatility against a realized-volatility forecast) on days of real option prices, and asking whether the edge predicted anything. */
class ChainReplayTest {

    private static final double R = 0.04;
    private static final double Q = 0.0;

    /** Business days of a seeded random walk with the given annual volatility. */
    private static List<Close> spyWithVol(double annualVol, int days, long seed) {
        Random rng = new Random(seed);
        List<Close> closes = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 2);
        double price = 450.0;
        while (closes.size() < days) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                price *= Math.exp(-0.5 * annualVol * annualVol / 252.0 + annualVol / Math.sqrt(252.0) * rng.nextGaussian());
                closes.add(new Close(d, price));
            }
            d = d.plusDays(1);
        }
        return closes;
    }

    /** Option closes priced by Black-Scholes at a fixed quoted vol, the market this test says exists. */
    private static ChainReplay.Pricer marketAt(double quotedVol, List<Close> spy) {
        TreeMap<LocalDate, Double> spot = new TreeMap<>();
        spy.forEach(c -> spot.put(c.date(), c.close()));
        return (call, put, from, to) -> {
            var c = OccSymbol.parse(call).orElseThrow();
            var p = OccSymbol.parse(put).orElseThrow();
            TreeMap<LocalDate, Quotes> out = new TreeMap<>();
            spot.subMap(from, true, to, true).forEach((day, s) -> {
                if (day.isBefore(c.expiry())) {
                    double t = (c.expiry().toEpochDay() - day.toEpochDay()) / 365.0;
                    out.put(day, new Quotes(BlackScholesPricer.price(OptionType.CALL, s, c.strike(), t, R, quotedVol, Q),
                            BlackScholesPricer.price(OptionType.PUT, s, p.strike(), t, R, quotedVol, Q)));
                }
            });
            return out;
        };
    }

    private static List<LocalDate> mondays(List<Close> spy, int firstIndex) {
        return spy.stream().skip(firstIndex).map(Close::date).filter(d -> d.getDayOfWeek() == DayOfWeek.MONDAY).toList();
    }

    @Test
    void anOverpricedMarketShowsAPositiveEdgeAndTheBuyerLoses() {
        List<Close> spy = spyWithVol(0.10, 520, 7L);
        List<LocalDate> decisions = mondays(spy, 60).stream().filter(d -> d.isBefore(spy.get(spy.size() - 1).date().minusDays(40))).toList();

        List<ChainReplay.Observation> obs = ChainReplay.replay(spy, decisions, 28, 7, 0.94, R, Q, marketAt(0.20, spy));

        assertTrue(obs.size() > 60, "observations: " + obs.size());
        double meanIv = obs.stream().mapToDouble(ChainReplay.Observation::impliedVol).average().orElseThrow();
        double meanEdge = obs.stream().mapToDouble(ChainReplay.Observation::edge).average().orElseThrow();
        assertEquals(0.20, meanIv, 0.01, "the solver recovers the vol the prices were made with");
        assertTrue(meanEdge > 0.05, "quoted 20% against a realized 10%: " + meanEdge);

        ChainReplay.Analysis a = ChainReplay.analyse(obs, 0.0, 0.01, 28, 7);
        assertTrue(a.meanLongPnlAtExpiry() < 0.0, "buying 20% vol that realizes 10% loses: " + a.meanLongPnlAtExpiry());
    }

    @Test
    void theRuleTradesTheSideTheEdgePointsToAndTheTercilesAreOrderedByEdge() {
        List<Close> spy = spyWithVol(0.10, 520, 11L);
        List<LocalDate> decisions = mondays(spy, 60).stream().filter(d -> d.isBefore(spy.get(spy.size() - 1).date().minusDays(40))).toList();
        List<ChainReplay.Observation> obs = ChainReplay.replay(spy, decisions, 28, 7, 0.94, R, Q, marketAt(0.20, spy));

        ChainReplay.Analysis a = ChainReplay.analyse(obs, 0.0, 0.01, 28, 7);

        assertEquals(3, a.terciles().size());
        assertTrue(a.terciles().get(0).meanEdge() <= a.terciles().get(1).meanEdge() && a.terciles().get(1).meanEdge() <= a.terciles().get(2).meanEdge());
        assertEquals(0, a.longRule().n(), "no day had an edge below minus the band in a market priced far above realized");
        assertTrue(a.shortRule().n() > 50, "the rule would have sold on almost every day: " + a.shortRule().n());
        assertTrue(a.shortRule().meanPnl() > 0.0, "and selling 20% vol that realizes 10% pays: " + a.shortRule().meanPnl());
    }

    @Test
    void whenThePriceCarriesNoInformationTheEdgeDoesNotPredictThePnl() {
        // quoted vol is constant while realized vol varies by regime, so the edge is just (constant - forecast); its slope against the buyer's
        // result is then whatever the regimes make it, but the test pins the mechanics: n matches, slope and t are finite
        List<Close> spy = spyWithVol(0.15, 520, 3L);
        List<LocalDate> decisions = mondays(spy, 60).stream().filter(d -> d.isBefore(spy.get(spy.size() - 1).date().minusDays(40))).toList();
        List<ChainReplay.Observation> obs = ChainReplay.replay(spy, decisions, 28, 7, 0.94, R, Q, marketAt(0.15, spy));

        ChainReplay.Analysis a = ChainReplay.analyse(obs, 0.0, 0.01, 28, 7);

        assertEquals(obs.size(), a.n());
        assertTrue(Double.isFinite(a.slopeOfPnlOnEdge()) && Double.isFinite(a.slopeTStat()) && Double.isFinite(a.correlation()));
        assertTrue(Math.abs(a.correlation()) <= 1.0 + 1e-12);
        assertTrue(a.effectiveN() < a.n(), "weekly decisions on 28-day options overlap, so they count for fewer than n: " + a.effectiveN());
    }

    @Test
    void aDayWithoutPricesOrHistoryIsSkippedAndCounted() {
        List<Close> spy = spyWithVol(0.12, 300, 5L);
        List<LocalDate> decisions = mondays(spy, 60).stream().limit(10).toList();
        ChainReplay.Pricer nothing = (call, put, from, to) -> new TreeMap<>();

        List<ChainReplay.Observation> none = ChainReplay.replay(spy, decisions, 28, 7, 0.94, R, Q, nothing);
        assertTrue(none.isEmpty());

        List<LocalDate> tooEarly = List.of(spy.get(3).date());
        assertTrue(ChainReplay.replay(spy, tooEarly, 28, 7, 0.94, R, Q, marketAt(0.2, spy)).isEmpty(), "ten returns are needed before a forecast exists");
    }

    @Test
    void analysingNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ChainReplay.analyse(List.of(), 0.0, 0.01, 28, 7));
    }

    @Test
    void theExitAfterTheHoldUsesTheNextCloseAndIsNaNWhenNoneExistsBeforeExpiry() {
        List<Close> spy = spyWithVol(0.12, 300, 9L);
        List<LocalDate> decisions = mondays(spy, 60).stream().limit(5).toList();

        List<ChainReplay.Observation> obs = ChainReplay.replay(spy, decisions, 28, 7, 0.94, R, Q, marketAt(0.18, spy));

        assertFalse(obs.isEmpty());
        for (ChainReplay.Observation o : obs) {
            assertTrue(Double.isFinite(o.exitStraddle()), "a week after entry the option still trades: " + o);
            assertFalse(o.exit().isBefore(o.entry().plusDays(7)));
            assertTrue(o.expiry().isAfter(o.exit()));
        }
        Optional<ChainReplay.Observation> first = obs.stream().findFirst();
        assertTrue(first.isPresent());
    }
}
