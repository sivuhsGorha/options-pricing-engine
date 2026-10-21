package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.volatility.OptionHistoryStudy.Quotes;
import com.sbk.optionspricer.volatility.VrpStudy.Close;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A straddle bought on a fixed schedule at the closing prices of the day and held to expiry, on real daily option prices: what it cost, what
 * the expiry paid, and so what a buyer made and a seller made. Hold-to-expiry on a calendar is not this engine's strategy, and a daily close has
 * no bid or ask, so costs are an assumption; it is a screen for whether the volatility premium survives costs, not a test of the engine.
 */
class OptionHistoryStudyTest {

    private static List<Close> weekdays(LocalDate from, int n, double start, double dailyStep) {
        List<Close> closes = new ArrayList<>();
        double price = start;
        LocalDate d = from;
        while (closes.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                closes.add(new Close(d, price));
                price += dailyStep;
            }
            d = d.plusDays(1);
        }
        return closes;
    }

    @Test
    void theMonthlyExpiriesAreTheThirdFridaysInTheRange() {
        List<LocalDate> expiries = OptionHistoryStudy.thirdFridays(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 6, 30));

        assertEquals(List.of(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 4, 19), LocalDate.of(2024, 5, 17), LocalDate.of(2024, 6, 21)), expiries);
    }

    @Test
    void everyFridayIsAWeeklyExpiry() {
        List<LocalDate> expiries = OptionHistoryStudy.fridays(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31));

        assertEquals(List.of(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 8), LocalDate.of(2024, 3, 15), LocalDate.of(2024, 3, 22), LocalDate.of(2024, 3, 29)), expiries);
    }

    @Test
    void aTradeIsPricedAtTheNearestStrikeToTheSpotAndPaysTheDistanceTheSpotMoved() {
        // spot 100 on the entry day, 106 at expiry: the 100 straddle costs 3.50 (call 2.00 + put 1.50) and pays 6.00
        List<Close> spy = weekdays(LocalDate.of(2024, 3, 1), 30, 100.0, 0.0);
        LocalDate expiry = LocalDate.of(2024, 3, 22);
        List<Close> path = new ArrayList<>();
        for (Close c : spy) {
            path.add(c.date().equals(expiry) ? new Close(c.date(), 106.0) : c);
        }

        OptionHistoryStudy.Result r = OptionHistoryStudy.simulate(path, List.of(expiry), 14,
                (call, put, entry) -> Optional.of(new Quotes(2.0, 1.5)));

        assertEquals(1, r.trades().size());
        OptionHistoryStudy.Trade t = r.trades().get(0);
        assertEquals(LocalDate.of(2024, 3, 8), t.entry());
        assertEquals(100.0, t.strike(), 1e-9);
        assertEquals(3.5, t.straddlePrice(), 1e-9);
        assertEquals(6.0, t.payoffAtExpiry(), 1e-9);
        // a buyer: 6.00 - 3.50 = +2.50 before costs; costs are two legs at 0.04 on the way in: +2.42
        assertEquals(2.5, t.grossLongPnl(), 1e-9);
        assertEquals(2.42, t.longPnl(0.04), 1e-9);
        // a seller makes the mirror, less the same costs: -2.50 - 0.08
        assertEquals(-2.58, t.shortPnl(0.04), 1e-9);
    }

    @Test
    void anEntryDayThatIsNotATradingDayFallsBackToTheLastTradingDayBeforeIt() {
        // 14 days before Friday 2024-03-22 is Friday 03-08; before Monday-after weekends the nearest earlier trading day is used
        List<Close> spy = weekdays(LocalDate.of(2024, 3, 1), 30, 100.0, 0.0);
        LocalDate expiry = LocalDate.of(2024, 3, 25); // a Monday: 15 days before is Sunday 03-10

        OptionHistoryStudy.Result r = OptionHistoryStudy.simulate(spy, List.of(expiry), 15, (call, put, entry) -> Optional.of(new Quotes(1.0, 1.0)));

        assertEquals(LocalDate.of(2024, 3, 8), r.trades().get(0).entry(), "Sunday 03-10 falls back to Friday 03-08");
    }

    @Test
    void aTradeWithNoPriceForALegIsSkippedAndCountedNotInvented() {
        List<Close> spy = weekdays(LocalDate.of(2024, 3, 1), 60, 100.0, 0.1);
        List<LocalDate> expiries = List.of(LocalDate.of(2024, 3, 22), LocalDate.of(2024, 4, 19));

        OptionHistoryStudy.Result r = OptionHistoryStudy.simulate(spy, expiries, 14,
                (call, put, entry) -> entry.isBefore(LocalDate.of(2024, 4, 1)) ? Optional.empty() : Optional.of(new Quotes(2.0, 2.0)));

        assertEquals(1, r.trades().size(), "only the second has prices");
        assertEquals(1, r.skippedNoPrice());
    }

    @Test
    void anExpiryBeyondTheDataOrBeforeTheFirstEntryIsSkippedSilentlyAsOutOfRange() {
        List<Close> spy = weekdays(LocalDate.of(2024, 3, 1), 20, 100.0, 0.0); // ends 2024-03-28
        List<LocalDate> expiries = List.of(LocalDate.of(2024, 3, 8), LocalDate.of(2024, 3, 22), LocalDate.of(2024, 6, 21));

        OptionHistoryStudy.Result r = OptionHistoryStudy.simulate(spy, expiries, 14, (call, put, entry) -> Optional.of(new Quotes(1.0, 1.0)));

        assertEquals(1, r.trades().size(), "03-22 has an entry on 03-08 and an expiry in the data; 03-08 has no entry day; 06-21 has no expiry close");
    }

    @Test
    void theSummaryGivesTheMeanTheWinRateTheWorstTradeAndTheFallFromAPeakForEachSide() {
        // three trades, spot 100 throughout, straddles costing 3.00, payoffs 5 (a win for the buyer), 0 (a win for the seller), 5
        List<OptionHistoryStudy.Trade> trades = new ArrayList<>();
        double[] payoffs = {5.0, 0.0, 5.0};
        for (int i = 0; i < 3; i++) {
            trades.add(new OptionHistoryStudy.Trade(LocalDate.of(2024, 3, 1).plusDays(30L * i), LocalDate.of(2024, 3, 31).plusDays(30L * i),
                    100.0, 100.0, 100.0, 1.5, 1.5, 3.0, payoffs[i]));
        }

        OptionHistoryStudy.Summary longSide = OptionHistoryStudy.summarise(trades, 0.0, true);
        OptionHistoryStudy.Summary shortSide = OptionHistoryStudy.summarise(trades, 0.0, false);

        assertEquals(3, longSide.n());
        assertEquals((2.0 - 3.0 + 2.0) / 3.0, longSide.meanPnl(), 1e-9);
        assertEquals(2.0 / 3.0, longSide.winRate(), 1e-9);
        assertEquals(-3.0, longSide.worstPnl(), 1e-9);
        assertEquals(3.0, longSide.maxDrawdown(), 1e-9, "+2, then -3 (cumulative -1), then +2: the fall from the peak of 2 to -1");
        assertEquals(1.0 / 3.0, shortSide.winRate(), 1e-9);
        assertEquals(-2.0, shortSide.worstPnl(), 1e-9);
        assertEquals(-longSide.meanPnl(), shortSide.meanPnl(), 1e-9, "with no costs the seller makes what the buyer loses");
    }

    @Test
    void theMeanStraddlePriceAgainstTheMeanMoveIsTheRatioOfWhatWasPaidToWhatWasEarned() {
        List<OptionHistoryStudy.Trade> trades = List.of(
                new OptionHistoryStudy.Trade(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31), 100.0, 100.0, 100.0, 2.0, 2.0, 4.0, 2.0),
                new OptionHistoryStudy.Trade(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31), 100.0, 100.0, 100.0, 2.0, 2.0, 4.0, 6.0));

        OptionHistoryStudy.Summary s = OptionHistoryStudy.summarise(trades, 0.0, true);

        assertEquals(4.0, s.meanStraddlePrice(), 1e-9);
        assertEquals(4.0, s.meanPayoff(), 1e-9);
        assertEquals(0.0, s.meanPnl(), 1e-9);
    }

    @Test
    void theYearsAreSummarisedSeparately() {
        List<OptionHistoryStudy.Trade> trades = List.of(
                new OptionHistoryStudy.Trade(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31), 100.0, 100.0, 100.0, 2.0, 2.0, 4.0, 2.0),
                new OptionHistoryStudy.Trade(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), 100.0, 100.0, 100.0, 2.0, 2.0, 4.0, 9.0));

        List<OptionHistoryStudy.YearRow> years = OptionHistoryStudy.byYear(trades, 0.0);

        assertEquals(List.of(2024, 2025), years.stream().map(OptionHistoryStudy.YearRow::year).toList());
        assertEquals(-2.0, years.get(0).longMeanPnl(), 1e-9);
        assertEquals(5.0, years.get(1).longMeanPnl(), 1e-9);
    }

    @Test
    void aStudyWithNoTradesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OptionHistoryStudy.summarise(List.of(), 0.04, true));
    }
}
