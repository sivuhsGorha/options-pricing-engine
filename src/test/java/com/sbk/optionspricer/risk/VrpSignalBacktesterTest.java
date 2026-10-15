package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.volatility.RealizedVolForecast;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VrpSignalBacktesterTest {

    private static final Instant T0 = Instant.parse("2026-10-14T14:30:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 20);

    /** About 16% realized volatility, as one close per day: the unit the forecaster is built for. */
    private static RealizedVolForecast forecastFromDailyCloses() {
        RealizedVolForecast forecast = new RealizedVolForecast(RealizedVolForecast.Model.EWMA);
        double price = 500.0;
        for (int i = 0; i < 25; i++) {
            price *= 1.0 + (i % 2 == 0 ? 0.01 : -0.01);
            forecast.observeSpot(price);
        }
        return forecast;
    }

    private static VrpSignalBacktester.Snapshot snapshot(int minute, double iv, double halfSpread) {
        double callPrice = 14.0 * (iv / 0.20);
        double putPrice = 13.5 * (iv / 0.20);
        List<OptionQuote> quotes = List.of(
                new OptionQuote("SPY", EXPIRY, 500.0, OptionType.CALL, callPrice - halfSpread, callPrice + halfSpread, iv, 100, 1000),
                new OptionQuote("SPY", EXPIRY, 500.0, OptionType.PUT, putPrice - halfSpread, putPrice + halfSpread, iv, 100, 1000));
        return new VrpSignalBacktester.Snapshot(T0.plusSeconds(60L * minute), new OptionChain("SPY", EXPIRY, 500.0, quotes));
    }

    /** A chain for any expiry; {@code scale} multiplies its prices so a mix-up between expiries shows up as a big number. */
    private static VrpSignalBacktester.Snapshot snapshotOn(LocalDate expiry, int minute, double iv, double halfSpread, double scale) {
        double callPrice = scale * 14.0 * (iv / 0.20);
        double putPrice = scale * 13.5 * (iv / 0.20);
        List<OptionQuote> quotes = List.of(
                new OptionQuote("SPY", expiry, 500.0, OptionType.CALL, callPrice - halfSpread, callPrice + halfSpread, iv, 100, 1000),
                new OptionQuote("SPY", expiry, 500.0, OptionType.PUT, putPrice - halfSpread, putPrice + halfSpread, iv, 100, 1000));
        return new VrpSignalBacktester.Snapshot(T0.plusSeconds(60L * minute), new OptionChain("SPY", expiry, 500.0, quotes));
    }

    @Test
    void severalExpiriesAtOneInstantAreOneTimeStepAndAnOpenPositionIsMarkedOnItsOwnExpiry() {
        // The recorder keeps every expiry of a chain in one file, so the reader returns one snapshot per expiry with
        // the same timestamp. They are one moment in time, not consecutive ones: opening on one expiry and closing
        // against another produced losses of thousands of dollars on a single contract.
        LocalDate far = LocalDate.of(2027, 1, 15);
        List<VrpSignalBacktester.Snapshot> sequence = List.of(
                snapshotOn(EXPIRY, 0, 0.26, 0.10, 1.0), snapshotOn(far, 0, 0.26, 0.10, 3.0),
                snapshotOn(EXPIRY, 1, 0.17, 0.10, 1.0), snapshotOn(far, 1, 0.17, 0.10, 3.0));

        VrpSignalBacktester.VrpBacktestResult result = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), 0.02, 1, 0.65);

        assertEquals(2, result.totalSnapshots(), "two moments in time, each with two expiries");
        assertEquals(1, result.totalTrades());
        assertEquals(EXPIRY, result.trades().get(0).expiry(), "the front expiry at least 14 days out");
        double soldAtBids = (18.2 - 0.10) + (17.55 - 0.10);       // iv 26%
        double boughtBackAtAsks = (11.9 + 0.10) + (11.475 + 0.10); // iv 17%
        assertEquals((soldAtBids - boughtBackAtAsks) * 100.0 - 4 * 0.65, result.totalPnl(), 1e-6);
    }

    @Test
    void aPositionIsMarkedAtItsOwnStrikeNotAtWhicheverStrikeIsAtTheMoneyNow() {
        // Spot rises from 500 to 520 between the two steps, so the at-the-money strike moves from 500 to 520.
        double iv0 = 0.26, iv1 = 0.17;
        List<OptionQuote> step0 = List.of(
                new OptionQuote("SPY", EXPIRY, 500.0, OptionType.CALL, 18.1, 18.3, iv0, 100, 1000),
                new OptionQuote("SPY", EXPIRY, 500.0, OptionType.PUT, 17.45, 17.65, iv0, 100, 1000));
        List<OptionQuote> step1 = List.of(
                new OptionQuote("SPY", EXPIRY, 500.0, OptionType.CALL, 30.0, 30.2, iv1, 100, 1000),   // the position's strike
                new OptionQuote("SPY", EXPIRY, 500.0, OptionType.PUT, 0.5, 0.7, iv1, 100, 1000),
                new OptionQuote("SPY", EXPIRY, 520.0, OptionType.CALL, 14.0, 14.2, iv1, 100, 1000),   // the new at-the-money strike
                new OptionQuote("SPY", EXPIRY, 520.0, OptionType.PUT, 13.5, 13.7, iv1, 100, 1000));
        List<VrpSignalBacktester.Snapshot> sequence = List.of(
                new VrpSignalBacktester.Snapshot(T0, new OptionChain("SPY", EXPIRY, 500.0, step0)),
                new VrpSignalBacktester.Snapshot(T0.plusSeconds(60), new OptionChain("SPY", EXPIRY, 520.0, step1)));

        VrpSignalBacktester.VrpBacktestResult result = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), 0.02, 1, 0.65);

        assertEquals(1, result.totalTrades());
        assertEquals(500.0, result.trades().get(0).strike(), 1e-9);
        assertEquals(30.2 + 0.7, result.trades().get(0).exitStraddlePrice(), 1e-9, "bought back at the asks of strike 500, not of 520");
        assertEquals(((18.1 + 17.45) - (30.2 + 0.7)) * 100.0 - 4 * 0.65, result.totalPnl(), 1e-6);
    }

    @Test
    void aPositionStillOpenWhenItsExpiryStopsAppearingIsClosedAtTheLastQuotesSeenNotAtItsEntryPrice() {
        LocalDate far = LocalDate.of(2027, 1, 15);
        List<VrpSignalBacktester.Snapshot> sequence = List.of(
                snapshotOn(EXPIRY, 0, 0.26, 0.10, 1.0),
                snapshotOn(far, 1, 0.26, 0.10, 3.0)); // the front expiry is not in the second step

        VrpSignalBacktester.VrpBacktestResult result = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), 0.02, 1, 0.65);

        assertEquals(1, result.totalTrades());
        double crossedSpread = 4 * 0.10 * 100.0;
        assertEquals(-(crossedSpread + 4 * 0.65), result.totalPnl(), 1e-6,
                "sold at the bids and marked at the asks last seen: the spread is lost, not just the fees");
    }

    @Test
    void anExpiryUnderTheMinimumDaysIsNeverOpenedOn() {
        LocalDate near = LocalDate.of(2026, 10, 19); // five days out
        VrpSignalBacktester.VrpBacktestResult result = VrpSignalBacktester.run(
                List.of(snapshotOn(near, 0, 0.26, 0.10, 1.0), snapshotOn(near, 1, 0.26, 0.10, 1.0)), forecastFromDailyCloses(), 0.02, 1, 0.65);

        assertEquals(0, result.totalTrades());
        assertEquals(0, result.evaluatedSteps());
    }

    @Test
    void theForecastOnADayUsesOnlyEarlierClosesNeverThatDaysOwn() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        List<com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose> closes = new ArrayList<>();
        double price = 500.0;
        for (int k = 30; k >= 1; k--) {
            price *= 1.0 + (k % 2 == 0 ? 0.01 : -0.01);
            closes.add(new com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose(day.minusDays(k), price));
        }
        List<VrpSignalBacktester.Snapshot> sequence = List.of(snapshot(0, 0.26, 0.10), snapshot(1, 0.26, 0.10));
        VrpSignalBacktester.ReplayConfig config = new VrpSignalBacktester.ReplayConfig(0.02, 1, 0.65, false, 0, false);

        VrpSignalBacktester.VrpBacktestResult baseline = VrpSignalBacktester.run(sequence, closes, RealizedVolForecast.Model.EWMA, config);

        List<com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose> withToday = new ArrayList<>(closes);
        withToday.add(new com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose(day, 1.0)); // a crash that has not happened yet
        VrpSignalBacktester.VrpBacktestResult sameDay = VrpSignalBacktester.run(sequence, withToday, RealizedVolForecast.Model.EWMA, config);
        assertEquals(baseline.meanVrp(), sameDay.meanVrp(), 1e-12, "the day's own close must not leak into its own forecast");

        List<com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose> withYesterday = new ArrayList<>(closes);
        withYesterday.set(closes.size() - 1, new com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose(day.minusDays(1), 1.0));
        VrpSignalBacktester.VrpBacktestResult earlier = VrpSignalBacktester.run(sequence, withYesterday, RealizedVolForecast.Model.EWMA, config);
        assertNotEquals(baseline.meanVrp(), earlier.meanVrp(), 1e-6, "an earlier close does move the forecast");
    }

    @Test
    void backtesterProcessesChainsAndGeneratesTradesOnVrpSignal() {
        // implied vol starts far above the realized forecast and falls back to it: a short straddle should win
        double[] ivs = {0.26, 0.25, 0.24, 0.21, 0.18, 0.17};
        List<VrpSignalBacktester.Snapshot> sequence = new ArrayList<>();
        for (int i = 0; i < ivs.length; i++) {
            sequence.add(snapshot(i, ivs[i], 0.10));
        }

        VrpSignalBacktester.VrpBacktestResult result = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), 0.02, 1, 0.65);

        assertEquals(ivs.length, result.totalSnapshots());
        assertTrue(result.totalTrades() >= 1, "At least one trade must be executed when VRP > 2 pts");
        assertTrue(result.totalPnl() > 0.0, "Shorting rich VRP that reverts must produce positive P&L: " + result.totalPnl());
        assertEquals(1.0, result.winRate(), 1e-6);
    }

    @Test
    void tradesAreDatedFromTheSnapshotsNotFromTheClockOnTheDayItRuns() {
        List<VrpSignalBacktester.Snapshot> sequence = List.of(snapshot(0, 0.26, 0.10), snapshot(1, 0.17, 0.10));

        VrpSignalBacktester.VrpBacktestResult result = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), 0.02, 1, 0.65);

        assertFalse(result.trades().isEmpty());
        assertEquals(LocalDate.of(2026, 10, 14), result.trades().get(0).date());
    }

    @Test
    void aSignalThatNeverPaysStillLosesTheSpreadAndTheFeesBecauseFillsAreAtTheTouch() {
        // The market never moves, so the position is closed at the last mark: sold at the bids, bought back at the
        // asks. Pricing both at the mid would report zero and make any signal look free to trade.
        double halfSpread = 0.10;
        List<VrpSignalBacktester.Snapshot> sequence = List.of(snapshot(0, 0.26, halfSpread), snapshot(1, 0.26, halfSpread), snapshot(2, 0.26, halfSpread));

        VrpSignalBacktester.VrpBacktestResult result = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), 0.02, 1, 0.65);

        assertEquals(1, result.totalTrades());
        double crossedSpread = 4 * halfSpread * 100.0; // two legs, each crossed in and out
        double fees = 4 * 0.65;                         // two legs, in and out
        assertEquals(-(crossedSpread + fees), result.totalPnl(), 1e-6);
        assertEquals(0.0, result.winRate(), 1e-6);
    }

    @Test
    void demeanedVrpFiltersOutConstantStructuralBias() {
        // A flat VRP series of +0.03 points (above the 0.02 threshold) across all steps
        List<VrpSignalBacktester.Snapshot> sequence = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sequence.add(snapshot(i, 0.20, 0.05));
        }

        // Without demeaning: flat level > threshold triggers trades
        VrpSignalBacktester.ReplayConfig rawConfig = new VrpSignalBacktester.ReplayConfig(0.02, 1, 0.65, false, 0, false);
        VrpSignalBacktester.VrpBacktestResult rawResult = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), rawConfig);

        // With demeaning: rolling window computes baseline, effective VRP becomes ~0, no trade opens
        VrpSignalBacktester.ReplayConfig demeanConfig = new VrpSignalBacktester.ReplayConfig(0.02, 1, 0.65, true, 5, false);
        VrpSignalBacktester.VrpBacktestResult demeanResult = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), demeanConfig);

        assertTrue(rawResult.totalTrades() >= 1, "Raw VRP opens trade on level");
        // Demeaned removes the level, so trades should be 0 or fewer
        assertTrue(demeanResult.totalTrades() <= rawResult.totalTrades(), "Demeaned VRP must dampen trades on flat bias");
    }

    @Test
    void costHurdleRejectsTradesWhenEdgeCannotCoverSpreadAndFees() {
        // IV is 0.19, fair is ~0.16 -> edge is ~0.03
        // With a huge spread (halfSpread = 2.0), friction / vega exceeds 0.03
        double wideHalfSpread = 2.0;
        List<VrpSignalBacktester.Snapshot> sequence = List.of(
                snapshot(0, 0.19, wideHalfSpread),
                snapshot(1, 0.19, wideHalfSpread)
        );

        // Without cost hurdle: opens trade
        VrpSignalBacktester.ReplayConfig noHurdle = new VrpSignalBacktester.ReplayConfig(0.02, 1, 0.65, false, 0, false);
        VrpSignalBacktester.VrpBacktestResult resNoHurdle = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), noHurdle);

        // With cost hurdle: rejects trade because friction hurdle > 0.03
        VrpSignalBacktester.ReplayConfig withHurdle = new VrpSignalBacktester.ReplayConfig(0.02, 1, 0.65, false, 0, true);
        VrpSignalBacktester.VrpBacktestResult resWithHurdle = VrpSignalBacktester.run(sequence, forecastFromDailyCloses(), withHurdle);

        assertEquals(1, resNoHurdle.totalTrades());
        assertEquals(0, resWithHurdle.totalTrades());
        assertTrue(resWithHurdle.hurdlesRejected() >= 1, "Hurdle rejection count must be positive");
    }
}
