package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.data.OptionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalReplayBacktesterTest {

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
