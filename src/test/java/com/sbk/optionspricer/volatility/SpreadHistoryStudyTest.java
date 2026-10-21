package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.volatility.SpreadHistoryStudy.Kind;
import com.sbk.optionspricer.volatility.SpreadHistoryStudy.Pricer;
import com.sbk.optionspricer.volatility.SpreadHistoryStudy.Trade;
import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Selling a defined-risk spread on a calendar, at real daily option closes: what it collected, what the expiry took back, and whether that beat zero by more than luck. */
class SpreadHistoryStudyTest {

    private static final double R = 0.04;

    private static List<Close> spy(double annualVol, int days, long seed) {
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

    /** Option closes from Black-Scholes at the vol this test says the market quotes, on the day of the entry. */
    private static Pricer market(double quotedVol, List<Close> closes) {
        TreeMap<LocalDate, Double> spot = new TreeMap<>();
        closes.forEach(c -> spot.put(c.date(), c.close()));
        return (symbols, entry) -> {
            double s = spot.floorEntry(entry).getValue();
            double[] prices = new double[symbols.size()];
            for (int i = 0; i < prices.length; i++) {
                var o = OccSymbol.parse(symbols.get(i)).orElseThrow();
                double t = (o.expiry().toEpochDay() - entry.toEpochDay()) / 365.0;
                prices[i] = BlackScholesPricer.price(o.type(), s, o.strike(), t, R, quotedVol, 0.0);
            }
            return Optional.of(prices);
        };
    }

    // ------------------------------------------------------------------ the arithmetic of one trade

    private static Trade condor(double credit, double spotAtExpiry) {
        // short 95 put / long 90 put, short 105 call / long 110 call
        return new Trade(Kind.IRON_CONDOR, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 20), 100.0, spotAtExpiry,
                new double[] {90, 95, 105, 110}, credit);
    }

    @Test
    void anIronCondorKeepsItsCreditInsideTheShortStrikesAndLosesAtMostTheWidthLessTheCredit() {
        assertEquals(1.50, condor(1.50, 100.0).grossPnl(), 1e-12, "inside: keeps all of it");
        assertEquals(-1.50, condor(1.50, 92.0).grossPnl(), 1e-12, "3 into the put spread: pays 3, keeps 1.50");
        assertEquals(1.50 - 5.0, condor(1.50, 80.0).grossPnl(), 1e-12, "far below: the width");
        assertEquals(1.50 - 5.0, condor(1.50, 130.0).grossPnl(), 1e-12, "far above: the width");
        assertEquals(1.50 - 2.0, condor(1.50, 107.0).grossPnl(), 1e-12);
        assertEquals(3.5, condor(1.50, 80.0).maxLoss(), 1e-12);
    }

    @Test
    void aPutSpreadOnlyHasTheTwoLowerLegs() {
        Trade put = new Trade(Kind.PUT_SPREAD, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 20), 100.0, 130.0, new double[] {90, 95}, 0.80);

        assertEquals(0.80, put.grossPnl(), 1e-12, "a rally never hurts a put spread");
        assertEquals(0.80 - 5.0, new Trade(Kind.PUT_SPREAD, put.entry(), put.expiry(), 100.0, 70.0, new double[] {90, 95}, 0.80).grossPnl(), 1e-12);
        assertEquals(2, put.legCount());
        assertEquals(4, condor(1.0, 100).legCount());
    }

    @Test
    void costsAreChargedOnceOnEveryLegAndNeverMakeALoserSmaller() {
        Trade t = condor(1.50, 100.0);

        assertEquals(1.50 - 4 * 0.05, t.pnl(0.05), 1e-12);
        assertTrue(condor(1.5, 80).pnl(0.05) < condor(1.5, 80).grossPnl());
    }

    // ------------------------------------------------------------------ the replay

    @Test
    void sellingRichOptionsInACalmMarketMakesMoneyAndSellingCheapOnesInAWildMarketLoses() {
        List<Close> calm = spy(0.10, 600, 4L);
        SpreadHistoryStudy.Result rich = SpreadHistoryStudy.simulate(calm, Kind.IRON_CONDOR, OptionHistoryStudy.thirdFridays(LocalDate.of(2024, 3, 1), LocalDate.of(2026, 4, 30)),
                30, 0.03, 5.0, market(0.25, calm));
        List<Close> wild = spy(0.30, 600, 4L);
        SpreadHistoryStudy.Result cheap = SpreadHistoryStudy.simulate(wild, Kind.IRON_CONDOR, OptionHistoryStudy.thirdFridays(LocalDate.of(2024, 3, 1), LocalDate.of(2026, 4, 30)),
                30, 0.03, 5.0, market(0.10, wild));

        assertTrue(rich.trades().size() >= 20, "trades: " + rich.trades().size());
        assertTrue(SpreadHistoryStudy.summarise(rich.trades(), 0.05, 1).meanPnl() > 0.0, "quoted 25% against a realized 10%");
        assertTrue(SpreadHistoryStudy.summarise(cheap.trades(), 0.05, 1).meanPnl() < 0.0, "quoted 10% against a realized 30%");
    }

    @Test
    void everyTradeIsInsideItsDefinedRisk() {
        List<Close> wild = spy(0.35, 600, 8L);
        SpreadHistoryStudy.Result r = SpreadHistoryStudy.simulate(wild, Kind.IRON_CONDOR, OptionHistoryStudy.thirdFridays(LocalDate.of(2024, 3, 1), LocalDate.of(2026, 4, 30)),
                30, 0.03, 5.0, market(0.20, wild));

        assertFalse(r.trades().isEmpty());
        for (Trade t : r.trades()) {
            assertTrue(t.grossPnl() >= -t.maxLoss() - 1e-9, "a defined-risk trade cannot lose more than its width less its credit: " + t);
            assertTrue(t.credit() > 0.0 && t.maxLoss() <= 5.0);
        }
    }

    @Test
    void anExpiryWithoutAllLegsPricedIsSkippedAndCountedNotInvented() {
        List<Close> closes = spy(0.12, 600, 2L);
        Pricer partial = (symbols, entry) -> Optional.empty();

        SpreadHistoryStudy.Result r = SpreadHistoryStudy.simulate(closes, Kind.PUT_SPREAD, OptionHistoryStudy.thirdFridays(LocalDate.of(2024, 3, 1), LocalDate.of(2026, 4, 30)),
                30, 0.03, 5.0, partial);

        assertTrue(r.trades().isEmpty());
        assertTrue(r.skippedNoPrice() > 10);
    }

    @Test
    void aCreditThatIsNotPositiveIsNotATradeAtAll() {
        List<Close> closes = spy(0.12, 600, 2L);
        Pricer worthless = (symbols, entry) -> Optional.of(new double[symbols.size()]); // every leg priced at zero

        SpreadHistoryStudy.Result r = SpreadHistoryStudy.simulate(closes, Kind.IRON_CONDOR, OptionHistoryStudy.thirdFridays(LocalDate.of(2024, 3, 1), LocalDate.of(2026, 4, 30)),
                30, 0.03, 5.0, worthless);

        assertTrue(r.trades().isEmpty());
    }

    // ------------------------------------------------------------------ the pass rule, written before the run

    private static List<Trade> trades(double... pnls) {
        List<Trade> list = new ArrayList<>();
        int i = 0;
        for (double p : pnls) {
            // a trade whose result is p with no costs: credit 1.0, payoff 1.0 - p (inside the strikes pays nothing, so build the loss with spot)
            LocalDate entry = LocalDate.of(2024 + i / 12, 1 + i % 12, 2);
            double payoff = 1.0 - p;
            double spot = payoff <= 0.0 ? 100.0 : 95.0 - payoff;
            list.add(new Trade(Kind.PUT_SPREAD, entry, entry.plusDays(30), 100.0, spot, new double[] {90, 95}, 1.0));
            i++;
        }
        return list;
    }

    @Test
    void theVerdictNeedsAPositiveMeanATStatisticAboveTwoPositiveYearsAndABoundedWorstTrade() {
        double[] steady = new double[36];
        java.util.Arrays.fill(steady, 0.30);
        for (int i = 0; i < steady.length; i += 6) steady[i] = 0.10; // small variation so the standard error is not zero
        SpreadHistoryStudy.Verdict good = SpreadHistoryStudy.evaluate(trades(steady), 0.0, 1, 500.0);

        assertTrue(good.positiveMean() && good.tStatisticAboveTwo() && good.everyYearPositive() && good.worstTradeAffordable(), good.toString());
        assertEquals("PASS", good.overall());
    }

    @Test
    void aPositiveMeanWithATStatisticBelowTwoIsUnprovenNotAPass() {
        double[] noisy = {1.0, -0.9, 0.8, -0.8, 0.9, -0.7, 0.9, -0.9, 0.6, -0.5, 0.7, -0.6, 0.8, -0.7, 0.6, -0.4, 0.7, -0.5, 0.9, -0.8, 0.5, -0.3, 0.4, -0.2, 0.6, -0.4, 0.5, -0.5, 0.3, -0.1};
        SpreadHistoryStudy.Verdict v = SpreadHistoryStudy.evaluate(trades(noisy), 0.0, 1, 500.0);

        assertTrue(v.positiveMean());
        assertFalse(v.tStatisticAboveTwo(), v.toString());
        assertEquals("UNPROVEN", v.overall());
    }

    @Test
    void aNegativeMeanOrALosingYearOrAnUnaffordableLossFails() {
        double[] losing = new double[24];
        java.util.Arrays.fill(losing, -0.2);
        assertEquals("FAIL", SpreadHistoryStudy.evaluate(trades(losing), 0.0, 1, 500.0).overall());

        double[] oneBadYear = new double[36];
        java.util.Arrays.fill(oneBadYear, 0.3);
        for (int i = 0; i < 12; i++) oneBadYear[i] = -0.1; // the first year loses, the other two win a lot
        SpreadHistoryStudy.Verdict v = SpreadHistoryStudy.evaluate(trades(oneBadYear), 0.0, 1, 500.0);
        assertFalse(v.everyYearPositive(), v.toString());
        assertNotEquals("PASS", v.overall());

        double[] tail = new double[30];
        java.util.Arrays.fill(tail, 0.4);
        tail[7] = -4.0; // one trade loses $400 a share-contract-equivalent: more than the affordable $300
        assertFalse(SpreadHistoryStudy.evaluate(trades(tail), 0.0, 1, 300.0).worstTradeAffordable());
    }
}
