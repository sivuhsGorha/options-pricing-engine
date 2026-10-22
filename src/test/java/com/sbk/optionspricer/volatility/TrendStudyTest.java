package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.volatility.TrendStudy.Perf;
import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trend-following on a daily index: long when the close is above its moving average, in cash when it is below, against simply holding. The rule that
 * says whether it earns its keep is fixed in code before any run, and is about risk, not about beating the market's return.
 */
class TrendStudyTest {

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

    private static double[] steadyRise(int n, double daily) {
        double[] p = new double[n];
        p[0] = 100;
        for (int i = 1; i < n; i++) p[i] = p[i - 1] * (1 + daily);
        return p;
    }

    /** Rises for {@code up} days, falls {@code down} days by the given daily moves, then rises again. */
    private static double[] boomBustBoom(int up, int down, int up2) {
        double[] p = new double[up + down + up2];
        p[0] = 100;
        for (int i = 1; i < p.length; i++) {
            double move = i < up ? 0.0015 : i < up + down ? -0.006 : 0.0015;
            p[i] = p[i - 1] * (1 + move);
        }
        return p;
    }

    @Test
    void inAnUnbrokenUptrendTheStrategyHoldsAndMatchesTheMarketAfterTheWarmUp() {
        List<Close> closes = series(steadyRise(600, 0.0005));

        TrendStudy.Result r = TrendStudy.run(closes, 200, 5.0, 0.0);

        assertEquals(0, r.trend().switches() - 1, "one entry at the start, then it never leaves");
        assertEquals(r.hold().cagr(), r.trend().cagr(), 0.002, "always invested, apart from a single entry cost");
        assertEquals(0.0, r.trend().maxDrawdown(), 1e-9);
        assertTrue(r.trend().timeInMarket() > 0.99);
    }

    @Test
    void aCrashAfterALongRiseIsPartlyAvoidedAndTheDrawdownIsSmaller() {
        List<Close> closes = series(boomBustBoom(400, 120, 300));

        TrendStudy.Result r = TrendStudy.run(closes, 100, 5.0, 0.0);

        assertTrue(r.hold().maxDrawdown() > 0.40, "the market fell hard: " + r.hold().maxDrawdown());
        assertTrue(r.trend().maxDrawdown() < 0.75 * r.hold().maxDrawdown(), "the trend rule stood aside for most of it: " + r.trend().maxDrawdown());
        assertTrue(r.trend().timeInMarket() < 1.0 && r.trend().switches() >= 2);
    }

    @Test
    void theSignalOfADayNeverEarnsTheReturnOfThatSameDay() {
        // flat for 150 days, then one huge jump on day 150, then flat: the close of day 150 is above its average because of the jump, so a rule that
        // acted on it the same day would capture the +50% and one that waits for the next day cannot
        double[] p = new double[260];
        java.util.Arrays.fill(p, 0, 150, 100.0);
        java.util.Arrays.fill(p, 150, 260, 150.0);

        TrendStudy.Result r = TrendStudy.run(series(p), 50, 0.0, 0.0);

        assertEquals(0.0, r.trend().totalReturn(), 1e-12, "it was in cash when the jump happened and bought after it");
        assertEquals(0.5, r.hold().totalReturn(), 1e-9);
    }

    @Test
    void everySwitchCostsTheStatedBasisPointsAndCashEarnsItsRate() {
        List<Close> closes = series(boomBustBoom(300, 100, 200));
        TrendStudy.Result free = TrendStudy.run(closes, 100, 0.0, 0.0);
        TrendStudy.Result costly = TrendStudy.run(closes, 100, 50.0, 0.0);
        TrendStudy.Result withCash = TrendStudy.run(closes, 100, 0.0, 0.04);

        assertEquals(free.trend().switches(), costly.trend().switches());
        assertTrue(costly.trend().totalReturn() < free.trend().totalReturn());
        double expectedDrag = Math.pow(1 - 0.005, free.trend().switches());
        assertEquals(free.trend().totalReturn() + 1, (costly.trend().totalReturn() + 1) / expectedDrag, 0.02, "about 50 bps lost per switch");
        assertTrue(withCash.trend().totalReturn() > free.trend().totalReturn(), "interest on the cash it holds while out");
    }

    @Test
    void badInputIsRefused() {
        List<Close> closes = series(steadyRise(300, 0.0005));
        assertThrows(IllegalArgumentException.class, () -> TrendStudy.run(closes, 1, 5.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> TrendStudy.run(closes, 200, -1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> TrendStudy.run(series(steadyRise(100, 0.001)), 200, 5.0, 0.0), "fewer closes than the window");
        assertThrows(IllegalArgumentException.class, () -> TrendStudy.run(null, 200, 5.0, 0.0));
    }

    // ------------------------------------------------------------------ the pass rule, written before the run

    @Test
    void aMarketWithADeepCrashAndRecoveryPassesTheRuleWhereTrendFollowingShouldHelp() {
        List<Close> closes = series(boomBustBoom(700, 150, 700));

        TrendStudy.Verdict v = TrendStudy.evaluate(closes, 200, 5.0, 0.0);

        assertTrue(v.drawdownMuchSmaller(), v.toString());
        assertNotEquals("FAIL", v.overall(), v.toString());
    }

    @Test
    void aWhipsawMarketFailsBecauseTheStrategyPaysCostsAndDoesNotAvoidTheFalls() {
        Random rng = new Random(5);
        double[] p = new double[1500];
        p[0] = 100;
        for (int i = 1; i < p.length; i++) {
            p[i] = p[i - 1] * (1 + 0.012 * rng.nextGaussian()); // a driftless, noisy market: the average is a coin toss
        }

        TrendStudy.Verdict v = TrendStudy.evaluate(series(p), 200, 10.0, 0.0);

        assertNotEquals("PASS", v.overall(), "no trend to follow, so the rule must not be able to pass here: " + v);
    }

    @Test
    void onDriftlessRandomMarketsTheRuleRarelyPassesWhichIsItsFalsePositiveRate() {
        int passes = 0, trials = 60;
        for (int seed = 0; seed < trials; seed++) {
            Random rng = new Random(1000 + seed);
            double[] p = new double[2500]; // about ten years
            p[0] = 100;
            for (int i = 1; i < p.length; i++) {
                p[i] = p[i - 1] * (1 + 0.01 * rng.nextGaussian()); // no drift and no trend: nothing for the rule to find
            }
            if ("PASS".equals(TrendStudy.evaluate(series(p), 200, 5.0, 0.0).overall())) {
                passes++;
            }
        }
        System.out.println("[TREND] false-positive rate of the pass rule on " + trials + " driftless random markets: " + passes + " passed");
        assertTrue(passes <= trials * 0.10, "the rule passed " + passes + " of " + trials + " markets that had nothing to find");
    }

    @Test
    void thePassNeedsEveryConditionAndTheVerdictNamesWhichFailed() {
        TrendStudy.Verdict allGood = new TrendStudy.Verdict(true, true, true, true, 0.0, 0.0, 0.0, 0.0);
        assertEquals("PASS", allGood.overall());

        assertEquals("UNPROVEN", new TrendStudy.Verdict(true, true, false, true, 0, 0, 0, 0).overall(), "one half of the data disagrees");
        assertEquals("UNPROVEN", new TrendStudy.Verdict(true, true, true, false, 0, 0, 0, 0).overall(), "only the chosen window works");
        assertEquals("FAIL", new TrendStudy.Verdict(false, true, true, true, 0, 0, 0, 0).overall(), "the drawdown was not meaningfully smaller");
        assertEquals("FAIL", new TrendStudy.Verdict(true, false, true, true, 0, 0, 0, 0).overall(), "risk-adjusted return was worse than holding");
    }

    @Test
    void performanceFiguresAreInternallyConsistent() {
        Perf hold = TrendStudy.run(series(boomBustBoom(300, 100, 200)), 100, 0.0, 0.0).hold();

        assertTrue(hold.maxDrawdown() >= 0.0 && hold.maxDrawdown() <= 1.0);
        assertTrue(hold.annualVol() > 0.0);
        assertEquals(hold.totalReturn(), Math.pow(1 + hold.cagr(), hold.years()) - 1, 1e-9);
        assertEquals(1.0, hold.timeInMarket(), 1e-12);
    }
}
