package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.volatility.VrpStudy.Close;
import com.sbk.optionspricer.volatility.VrpStudy.Observation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Implied volatility (the VIX) against the volatility that followed, on SPY's own closes: the premium, how often it was
 * positive, and what a variance-swap-like short position would have lost in the bad months. It is a study of the premium,
 * not a backtest of any trade: no costs, no margin, no options prices.
 */
class VrpStudyTest {

    private static final double ANNUALISE = Math.sqrt(252.0);

    private static LocalDate day(int i) {
        return LocalDate.of(2020, 1, 1).plusDays(i);
    }

    /** Closes whose daily log returns are +-size alternating, one per calendar day (the study counts trading days by position). */
    private static List<Close> alternating(int n, double size) {
        List<Close> list = new ArrayList<>();
        double price = 100.0;
        for (int i = 0; i < n; i++) {
            price *= Math.exp(i == 0 ? 0.0 : (i % 2 == 0 ? 1 : -1) * size);
            list.add(new Close(day(i), price));
        }
        return list;
    }

    private static Map<LocalDate, Double> constantVix(int n, double level) {
        Map<LocalDate, Double> vix = new HashMap<>();
        for (int i = 0; i < n; i++) vix.put(day(i), level);
        return vix;
    }

    @Test
    void thePremiumIsTheVixLessTheVolatilityOfTheNext21Days() {
        List<Observation> obs = VrpStudy.observations(alternating(60, 0.01), constantVix(60, 20.0), 21);

        assertEquals(60 - 21, obs.size(), "one per day that has 21 returns after it");
        double realized = 0.01 * ANNUALISE;
        for (Observation o : obs) {
            assertEquals(0.20, o.impliedVol(), 1e-12);
            assertEquals(realized, o.realizedVol(), 1e-9);
        }
        assertEquals(100 * (0.20 - realized), obs.get(0).premiumPoints(), 1e-9);
        // a short variance position sized at $1 a vol point: (IV^2 - RV^2) / (2 IV), in vol points
        assertEquals(100 * (0.20 * 0.20 - realized * realized) / (2 * 0.20), obs.get(0).payoffPoints(), 1e-9);
    }

    @Test
    void theRealizedVolatilityUsesOnlyDaysAfterTheObservationDate() {
        // 60 calm days (0.2% a day), then 40 turbulent ones (3%)
        List<Close> closes = new ArrayList<>();
        double price = 100.0;
        for (int i = 0; i < 100; i++) {
            price *= Math.exp(i == 0 ? 0.0 : (i % 2 == 0 ? 1 : -1) * (i < 60 ? 0.002 : 0.03));
            closes.add(new Close(day(i), price));
        }

        List<Observation> obs = VrpStudy.observations(closes, constantVix(100, 15.0), 21);

        Observation calm = obs.get(0);          // days 1..21 are all calm
        Observation beforeStorm = obs.get(45);  // days 46..66 include six turbulent ones
        assertTrue(calm.realizedVol() < 0.05, "calm window " + calm.realizedVol());
        assertTrue(beforeStorm.realizedVol() > calm.realizedVol() * 3, "the window that reaches into the storm: " + beforeStorm.realizedVol());
        assertTrue(beforeStorm.premiumPoints() < 0.0, "the storm is not in the VIX of that day, so the premium is negative");
    }

    @Test
    void aDayWithoutAVixReadingIsSkippedNotInvented() {
        Map<LocalDate, Double> vix = constantVix(60, 18.0);
        vix.remove(day(5));

        List<Observation> obs = VrpStudy.observations(alternating(60, 0.01), vix, 21);

        assertEquals(60 - 21 - 1, obs.size());
        assertTrue(obs.stream().noneMatch(o -> o.date().equals(day(5))));
    }

    @Test
    void theSummaryReportsHowOftenThePremiumWasPositiveAndWhatTheDistributionLooksLike() {
        List<Observation> obs = new ArrayList<>();
        // premiums, in vol points, of 10 days: nine of +4 and one of -8
        for (int i = 0; i < 10; i++) {
            double premium = i == 9 ? -8.0 : 4.0;
            obs.add(new Observation(day(i * 21), i * 21, 0.20, 0.20 - premium / 100.0));
        }

        VrpStudy.Result r = VrpStudy.study(obs, 21);

        assertEquals(10, r.observations());
        assertEquals(0.9, r.shareWithPositivePremium(), 1e-12);
        assertEquals((9 * 4.0 - 8.0) / 10.0, r.meanPremiumPoints(), 1e-9);
        assertEquals(4.0, r.medianPremiumPoints(), 1e-9);
        assertEquals(-8.0, r.minPremiumPoints(), 1e-9);
    }

    @Test
    void theMonthlyRowsDoNotOverlapAndTheWorstMonthAndTheDrawdownAreFound() {
        // payoffs in vol points, built from IV and RV so payoffPoints() is what is asserted: +4, -10, +4
        List<Observation> obs = new ArrayList<>();
        double[] payoffs = {4.0, -10.0, 4.0};
        for (int k = 0; k < 3; k++) {
            // payoff = (IV^2 - RV^2) / (2 IV) * 100 with IV 0.20: RV^2 = IV^2 - 2 IV payoff / 100
            double rv = Math.sqrt(0.04 - 2 * 0.20 * payoffs[k] / 100.0);
            obs.add(new Observation(day(k * 21), k * 21, 0.20, rv));
            // an overlapping neighbour that must not be counted as another month
            obs.add(new Observation(day(k * 21 + 1), k * 21 + 1, 0.20, rv));
        }

        VrpStudy.Result r = VrpStudy.study(obs, 21);

        assertEquals(3, r.monthly().months(), "overlapping days are not extra months");
        assertEquals(-10.0, r.monthly().worstPayoffPoints(), 1e-9);
        assertEquals(day(21), r.monthly().worstDate());
        assertEquals(10.0, r.monthly().maxDrawdownPoints(), 1e-9, "4, then -6, then -2: the fall from the peak of 4 to -6 is 10");
        assertEquals(1.0 / 3.0, r.monthly().shareNegative(), 1e-12);
    }

    @Test
    void theStudyIsSplitByTheVixLevelAtTheTime() {
        List<Observation> obs = new ArrayList<>();
        double[] levels = {12.0, 17.0, 25.0, 40.0};
        for (int i = 0; i < levels.length; i++) obs.add(new Observation(day(i * 21), i * 21, levels[i] / 100.0, levels[i] / 100.0 - 0.03));

        VrpStudy.Result r = VrpStudy.study(obs, 21);

        assertEquals(4, r.buckets().size());
        assertEquals(List.of("VIX under 15", "VIX 15 to 20", "VIX 20 to 30", "VIX 30 and over"), r.buckets().stream().map(VrpStudy.Bucket::label).toList());
        assertTrue(r.buckets().stream().allMatch(b -> b.n() == 1), r.buckets().toString());
    }

    @Test
    void theYearsAreReportedSeparately() {
        List<Observation> obs = new ArrayList<>();
        obs.add(new Observation(LocalDate.of(2018, 2, 5), 0, 0.17, 0.30));
        obs.add(new Observation(LocalDate.of(2019, 3, 5), 21, 0.15, 0.10));

        VrpStudy.Result r = VrpStudy.study(obs, 21);

        assertEquals(List.of(2018, 2019), r.years().stream().map(VrpStudy.YearRow::year).toList());
        assertTrue(r.years().get(0).meanPremiumPoints() < 0.0 && r.years().get(1).meanPremiumPoints() > 0.0);
    }

    @Test
    void aStudyWithNothingToStudyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> VrpStudy.study(List.of(), 21));
        assertThrows(IllegalArgumentException.class, () -> VrpStudy.observations(alternating(10, 0.01), constantVix(10, 20.0), 21),
                "fewer closes than the horizon");
    }

    @Test
    void theVixFileIsReadAndAnythingElseInItIsSkipped() {
        Map<LocalDate, Double> vix = VrpStudy.parseVixCsv(List.of(
                "DATE,OPEN,HIGH,LOW,CLOSE",
                "01/02/1990,17.240000,17.240000,17.240000,17.240000",
                "10/16/2026,16.020000,22.310000,15.720000,19.990000",
                "not a date,1,2,3,4",
                "10/17/2026,1,2,3,",
                "10/18/2026,1,2,3,-5"));

        assertEquals(2, vix.size());
        assertEquals(17.24, vix.get(LocalDate.of(1990, 1, 2)), 1e-12);
        assertEquals(19.99, vix.get(LocalDate.of(2026, 10, 16)), 1e-12);
    }

    @Test
    void aHoleInTheClosesIsNeverReadAsADayOfTradingSoNoWindowSpansIt() {
        // Found in the first real run: Alpaca's free feed had no bars for 2019, and the 21 "next days" after a 2018 close ran
        // into 2020, a realised volatility of 61% that was a gap in the data. A window with a gap of more than a week is dropped.
        List<Close> closes = new ArrayList<>();
        double price = 100.0;
        for (int i = 0; i < 102; i++) {
            int dayNumber = i < 50 ? i : i + 60; // 60 calendar days missing after the 50th close
            price *= Math.exp(i == 0 ? 0.0 : (i % 2 == 0 ? 1 : -1) * 0.01);
            closes.add(new Close(day(dayNumber), price));
        }
        Map<LocalDate, Double> vix = new HashMap<>();
        closes.forEach(c -> vix.put(c.date(), 20.0));

        List<Observation> obs = VrpStudy.observations(closes, vix, 21);

        assertEquals(102 - 21 - 21, obs.size(), "the 21 windows that reach across the hole are dropped");
        assertTrue(obs.stream().noneMatch(o -> o.index() >= 29 && o.index() <= 49), "none of the windows that include the hole");
        assertTrue(obs.stream().allMatch(o -> o.realizedVol() < 0.2), "no window is inflated by the missing months");
    }
}
