package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.data.OptionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalReplayBacktesterTest {

    /** A snapshot whose mid is exactly {@code mid}, one minute after the previous index. */
    private static OptionSnapshot at(int minute, double mid) {
        return new OptionSnapshot(Instant.parse("2024-01-02T09:30:00Z").plusSeconds(60L * minute), "SPY", LocalDate.of(2024, 1, 19),
                510.0, OptionType.CALL, 510.0, mid - 0.5, mid + 0.5, 0.25, 1000, 2000);
    }

    // Found on review (2026-10-10): maxDrawdown was the lowest cumulative P&L, not the largest fall from a peak, so a
    // run that made 20 and gave all of it back reported 0; and a position still open at the end of the data counted
    // as two trades.

    @Test
    void maxDrawdownIsTheLargestFallFromAPeakInCumulativeP_and_L() {
        // 100 -> 110 opens at 110; 130 closes it, +20. 140 opens at 140; 120 closes it, -20. Cumulative: +20, then 0.
        List<OptionSnapshot> path = List.of(at(0, 100), at(1, 110), at(2, 130), at(3, 130), at(4, 140), at(5, 120));

        HistoricalReplayBacktester.ReplayResult result = HistoricalReplayBacktester.runReplay(path, 1, 5.0);

        assertEquals(0.0, result.totalPnL(), 1e-9);
        assertEquals(20.0, result.maxDrawdown(), 1e-9, "peak +20 to trough 0; the lowest P&L alone is 0");
        assertEquals(2, result.tradeCount());
    }

    @Test
    void aRunThatOnlyEverGainsHasNoDrawdown() {
        List<OptionSnapshot> path = List.of(at(0, 100), at(1, 110), at(2, 130));

        HistoricalReplayBacktester.ReplayResult result = HistoricalReplayBacktester.runReplay(path, 1, 5.0);

        assertEquals(20.0, result.totalPnL(), 1e-9);
        assertEquals(0.0, result.maxDrawdown(), 1e-9);
    }

    @Test
    void aRunThatOnlyEverLosesFallsFromTheStartingZero() {
        // Opens at 110, closes at 90: -20 from a starting peak of 0.
        List<OptionSnapshot> path = List.of(at(0, 100), at(1, 110), at(2, 90));

        HistoricalReplayBacktester.ReplayResult result = HistoricalReplayBacktester.runReplay(path, 1, 5.0);

        assertEquals(-20.0, result.totalPnL(), 1e-9);
        assertEquals(20.0, result.maxDrawdown(), 1e-9);
    }

    @Test
    void aPositionStillOpenAtTheEndIsCloseOutAtTheLastPriceAndCountsOnce() {
        // Opens at 110; 111 is inside the 5% band, so the data ends with the position open and is marked at 111.
        List<OptionSnapshot> path = List.of(at(0, 100), at(1, 110), at(2, 111));

        HistoricalReplayBacktester.ReplayResult result = HistoricalReplayBacktester.runReplay(path, 1, 5.0);

        assertEquals(1, result.tradeCount(), "one entry is one trade, whether it closed in the loop or at the end");
        assertEquals(1.0, result.totalPnL(), 1e-9);
    }

    @Test
    void replaysHistoricalSnapshotsIntoPnLAndSlippageMetrics() {
        List<OptionSnapshot> snapshots = List.of(
                new OptionSnapshot(Instant.parse("2024-01-02T09:30:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 510.0, 509.5, 510.5, 0.22, 1000, 2000),
                new OptionSnapshot(Instant.parse("2024-01-02T09:31:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.5, 511.8, 513.2, 0.25, 1200, 2100),
                new OptionSnapshot(Instant.parse("2024-01-02T09:32:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 515.0, 514.4, 515.6, 0.28, 1300, 2200),
                new OptionSnapshot(Instant.parse("2024-01-02T09:33:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.0, 511.7, 512.3, 0.30, 1250, 2150)
        );

        HistoricalReplayBacktester.ReplayResult result = HistoricalReplayBacktester.runReplay(snapshots, 100, 0.45);

        assertEquals(4, result.snapshotsProcessed());
        assertTrue(result.tradeCount() >= 1, "at least one trade should be generated");
        assertTrue(result.totalPnL() != 0.0, "P&L should reflect the price move");
        assertTrue(result.averageSlippage() >= 0.0, "slippage must never be negative");
    }
}
