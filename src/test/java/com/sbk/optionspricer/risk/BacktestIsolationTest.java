package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.data.OptionSnapshot;
import com.sbk.optionspricer.execution.PositionTracker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A backtest must not touch live state: not the dashboard-visible tracker and not the fill ledger. */
class BacktestIsolationTest {

    private static final Path LEDGER = Path.of("target", "fill_ledger.csv");

    private static List<OptionSnapshot> snapshots() {
        return List.of(
                new OptionSnapshot(Instant.parse("2024-01-02T09:30:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 510.0, 509.5, 510.5, 0.22, 1000, 2000),
                new OptionSnapshot(Instant.parse("2024-01-02T09:31:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.5, 511.8, 513.2, 0.25, 1200, 2100),
                new OptionSnapshot(Instant.parse("2024-01-02T09:32:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 515.0, 514.4, 515.6, 0.28, 1300, 2200),
                new OptionSnapshot(Instant.parse("2024-01-02T09:33:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.0, 511.7, 512.3, 0.30, 1250, 2150));
    }

    private static long ledgerLines() throws IOException {
        return Files.exists(LEDGER) ? Files.readAllLines(LEDGER).size() : 0L;
    }

    @Test
    void backtestDoesNotChangeTheLiveTrackersExposureSnapshot() {
        PositionTracker live = new PositionTracker();
        live.applyFill(new PositionTracker.ExecutionFill("SPY", 7, 1, 500.0));
        PositionTracker.PortfolioExposure before = live.snapshotExposure();

        PortfolioBacktestOrchestrator.run(snapshots());

        assertEquals(before, live.snapshotExposure(),
                "backtest fills must not leak into the exposure the dashboard reports");
    }

    @Test
    void backtestDoesNotWriteToTheLiveFillLedger() throws IOException {
        long before = ledgerLines();

        PortfolioBacktestOrchestrator.run(snapshots());

        assertEquals(before, ledgerLines(), "backtest fills must not be appended to the authoritative ledger");
    }

    @Test
    void backtestPnLIsTheReplayPnLWithNoInventedTerm() {
        PortfolioBacktestOrchestrator.PortfolioBacktestResult result = PortfolioBacktestOrchestrator.run(snapshots());
        HistoricalReplayBacktester.ReplayResult replay = HistoricalReplayBacktester.runReplay(snapshots(), 10, 0.45);

        assertEquals(replay.totalPnL(), result.totalPnL(), 1e-12);
    }
}
