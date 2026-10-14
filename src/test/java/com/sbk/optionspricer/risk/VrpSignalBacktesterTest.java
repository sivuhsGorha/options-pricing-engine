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
}
