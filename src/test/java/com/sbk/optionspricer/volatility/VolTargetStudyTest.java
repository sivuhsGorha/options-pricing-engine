package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Volatility targeting on a daily index: hold less when the forecast volatility is high and more (up to fully invested) when it is low, against simply holding.
 * A rule that holds less cuts the fall from a peak by construction, so the pass rule is about return per unit of risk, with a margin calibrated on simulated markets.
 */
class VolTargetStudyTest {

    private static List<Close> series(double[] prices) {
        List<Close> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2016, 1, 4);
        for (double p : prices) {
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
            out.add(new Close(d, p));
            d = d.plusDays(1);
        }
        return out;
    }

    /** A random walk whose daily volatility is {@code dailyVol(i)}. */
    private static double[] walk(int n, long seed, java.util.function.IntToDoubleFunction dailyVol, double drift) {
        Random rng = new Random(seed);
        double[] p = new double[n];
        p[0] = 100;
        for (int i = 1; i < n; i++) {
            p[i] = p[i - 1] * (1 + drift + dailyVol.applyAsDouble(i) * rng.nextGaussian());
        }
        return p;
    }

    @Test
    void whenVolatilityIsAlwaysBelowTheTargetTheStrategyIsFullyInvestedAndMatchesHolding() {
        List<Close> closes = series(walk(800, 3, i -> 0.003, 0.0003)); // about 5% a year, under a 10% target

        VolTargetStudy.Result r = VolTargetStudy.run(closes, 0.10, 0.94, 0.0);

        assertEquals(1.0, r.target().timeInMarket(), 0.01, "average exposure");
        assertEquals(r.hold().cagr(), r.target().cagr(), 0.002);
        assertEquals(r.hold().sharpe(), r.target().sharpe(), 0.02);
    }

    @Test
    void whenVolatilityIsTwiceTheTargetTheExposureSettlesNearHalf() {
        // 0.0126 daily is about 20% a year, twice a 10% target
        List<Close> closes = series(walk(1500, 4, i -> 0.0126, 0.0));

        VolTargetStudy.Result r = VolTargetStudy.run(closes, 0.10, 0.94, 0.0);

        assertEquals(0.5, r.target().timeInMarket(), 0.1, "average exposure: " + r.target().timeInMarket());
        assertTrue(r.target().annualVol() < 0.75 * r.hold().annualVol(), "the point of the rule: " + r.target().annualVol() + " against " + r.hold().annualVol());
    }

    @Test
    void aVolatilitySpikeIsMetWithLessExposureOnlyAfterItHasBeenSeen() {
        // calm for 300 days, then one -8% day, then calm again: the -8% day itself is taken at full exposure, the days after it at less
        double[] p = new double[420];
        p[0] = 100;
        for (int i = 1; i < p.length; i++) {
            double move = i == 300 ? -0.08 : (i % 2 == 0 ? 0.002 : -0.002);
            p[i] = p[i - 1] * (1 + move);
        }

        VolTargetStudy.Result r = VolTargetStudy.run(series(p), 0.10, 0.94, 0.0);

        // the whole of the -8% day is lost: the rule cannot have known
        double dayReturn = r.target().worstDay();
        assertEquals(r.hold().worstDay(), dayReturn, 0.005, "full exposure going into the shock: " + dayReturn + " against " + r.hold().worstDay());
        assertTrue(r.target().timeInMarket() < 1.0, "afterwards it held less");
    }

    @Test
    void everyChangeOfExposureCostsTheStatedBasisPointsOnTheAmountTraded() {
        List<Close> closes = series(walk(900, 5, i -> i % 200 < 100 ? 0.006 : 0.016, 0.0003));
        VolTargetStudy.Result free = VolTargetStudy.run(closes, 0.10, 0.94, 0.0);
        VolTargetStudy.Result costly = VolTargetStudy.run(closes, 0.10, 0.94, 50.0);

        assertTrue(costly.target().totalReturn() < free.target().totalReturn());
        assertEquals(free.target().turnover(), costly.target().turnover(), 1e-12);
        assertEquals(free.hold().totalReturn(), costly.hold().totalReturn(), 1e-12, "holding trades once and is not charged");
    }

    @Test
    void badInputIsRefused() {
        List<Close> closes = series(walk(400, 1, i -> 0.01, 0.0));
        assertThrows(IllegalArgumentException.class, () -> VolTargetStudy.run(closes, 0.0, 0.94, 0.0));
        assertThrows(IllegalArgumentException.class, () -> VolTargetStudy.run(closes, 0.10, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> VolTargetStudy.run(closes, 0.10, 0.94, -1.0));
        assertThrows(IllegalArgumentException.class, () -> VolTargetStudy.run(series(walk(50, 1, i -> 0.01, 0.0)), 0.10, 0.94, 0.0));
        assertThrows(IllegalArgumentException.class, () -> VolTargetStudy.run(null, 0.10, 0.94, 0.0));
    }

    // ------------------------------------------------------------------ the rule, with its margin calibrated on simulated markets

    @Test
    void thePassNeedsEveryConditionAndTheVerdictNamesWhichFailed() {
        assertEquals("PASS", new VolTargetStudy.Verdict(true, true, true, true, 0, 0, 0, 0).overall());
        assertEquals("UNPROVEN", new VolTargetStudy.Verdict(true, true, false, true, 0, 0, 0, 0).overall());
        assertEquals("UNPROVEN", new VolTargetStudy.Verdict(true, true, true, false, 0, 0, 0, 0).overall());
        assertEquals("FAIL", new VolTargetStudy.Verdict(false, true, true, true, 0, 0, 0, 0).overall());
        assertEquals("FAIL", new VolTargetStudy.Verdict(true, false, true, true, 0, 0, 0, 0).overall());
    }

    @Test
    void onSimulatedMarketsWithNothingToFindTheRuleRarelyPassesWhichIsItsFalsePositiveRate() {
        int passes = 0, trials = 60;
        for (int seed = 0; seed < trials; seed++) {
            // zero drift, volatility that clusters (a persistent random level), so there is a real regime effect but no reward for avoiding it
            Random level = new Random(7000 + seed);
            double[] vol = new double[2500];
            double state = Math.log(0.01);
            for (int i = 0; i < vol.length; i++) {
                state = 0.98 * state + 0.02 * Math.log(0.01) + 0.08 * level.nextGaussian();
                vol[i] = Math.exp(state);
            }
            double[] p = walk(2500, 8000 + seed, i -> vol[i], 0.0);
            if ("PASS".equals(VolTargetStudy.evaluate(series(p), 0.10, 0.94, 5.0).overall())) {
                passes++;
            }
        }
        System.out.println("[VOLTARGET] false-positive rate of the pass rule on " + trials + " zero-drift markets with clustering volatility: " + passes + " passed");
        assertTrue(passes <= trials * 0.10, "the rule passed " + passes + " of " + trials + " markets with nothing to find");
    }
}
