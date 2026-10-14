package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PnlAttributionEngineTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-14T14:00:00Z"); // 4 hours later

    @Test
    void pureDeltaAttributionOnStockPosition() {
        PnlAttributionEngine engine = new PnlAttributionEngine();

        Valuation.PositionValuation stock0 = new Valuation.PositionValuation(
                "SPY", "STOCK", 100, 1, 100.0, "SPOT", 100.0, 0.0,
                Double.NaN, "NONE", 1.0, 0.0, 0.0, 0.0, 0.0, false, null
        );
        Valuation v0 = new Valuation(T0, 100.0, "LIVE", List.of(stock0), 0.0, 0.0,
                100.0, 0.0, 0.0, 0.0, 0.0, List.of());

        engine.update(v0);

        // Spot rises from 100 to 102.5. Total P&L is 100 * 2.5 = 250.0
        Valuation.PositionValuation stock1 = new Valuation.PositionValuation(
                "SPY", "STOCK", 100, 1, 102.5, "SPOT", 100.0, 250.0,
                Double.NaN, "NONE", 1.0, 0.0, 0.0, 0.0, 0.0, false, null
        );
        Valuation v1 = new Valuation(T1, 102.5, "LIVE", List.of(stock1), 250.0, 0.0,
                100.0, 0.0, 0.0, 0.0, 0.0, List.of());

        PnlAttribution attr = engine.update(v1);

        assertEquals(2.5, attr.spotChange(), 1e-9);
        assertEquals(250.0, attr.totalPnlChange(), 1e-6);
        assertEquals(250.0, attr.deltaPnl(), 1e-6, "Delta explains 100 shares * $2.50");
        assertEquals(0.0, attr.gammaPnl(), 1e-9);
        assertEquals(0.0, attr.thetaPnl(), 1e-9);
        assertEquals(0.0, attr.vegaPnl(), 1e-9);
        assertEquals(0.0, attr.residualPnl(), 1e-6, "Zero residual for linear asset");
        assertEquals(250.0, attr.cumulativeDeltaPnl(), 1e-6);
    }

    @Test
    void gammaAndThetaAttributionOnOptionPosition() {
        PnlAttributionEngine engine = new PnlAttributionEngine();

        // 1 contract = 100 shares. Delta = 0.50 (Net 50), Gamma = 0.08 (Net 8.0), Theta = -365.0/yr (Net -365)
        Valuation.PositionValuation opt0 = new Valuation.PositionValuation(
                "SPY261120C00100000", "OPTION", 1, 100, 3.00, "MID", 3.00, 0.0,
                0.20, "SVI", 0.50, 0.08, 20.0, -3.65, 0.0, false, null
        );
        Valuation v0 = new Valuation(T0, 100.0, "LIVE", List.of(opt0), 0.0, 0.0,
                50.0, 8.0, 2000.0, -365.0, 0.0, List.of());

        engine.update(v0);

        // Spot moves by +$2.00, time advances by 1 day (1/365 year)
        Instant tNext = T0.plusSeconds(86400);
        // By Taylor:
        // Delta = 50 * 2.0 = 100.0
        // Gamma = 0.5 * 8.0 * (2.0)^2 = 16.0
        // Theta = -365.0 * (1.0 / 365.0) = -1.0
        // Total expected = 100.0 + 16.0 - 1.0 = 115.0
        double simulatedPrice = 3.00 + 1.15; // +$1.15 per share = +$115 total PnL
        Valuation.PositionValuation opt1 = new Valuation.PositionValuation(
                "SPY261120C00100000", "OPTION", 1, 100, simulatedPrice, "MID", 3.00, 115.0,
                0.20, "SVI", 0.66, 0.07, 18.0, -3.80, 0.0, false, null
        );
        Valuation v1 = new Valuation(tNext, 102.0, "LIVE", List.of(opt1), 115.0, 0.0,
                66.0, 7.0, 1800.0, -380.0, 0.0, List.of());

        PnlAttribution attr = engine.update(v1);

        assertEquals(2.0, attr.spotChange(), 1e-9);
        assertEquals(100.0, attr.deltaPnl(), 1e-6);
        assertEquals(16.0, attr.gammaPnl(), 1e-6);
        assertEquals(-1.0, attr.thetaPnl(), 0.01);
        assertEquals(15.0, attr.gammaThetaPnl(), 0.01);
        assertEquals(0.0, attr.vegaPnl(), 1e-6);
        assertEquals(0.0, attr.residualPnl(), 0.01);
    }

    @Test
    void vegaAttributionOnVolatilityShock() {
        PnlAttributionEngine engine = new PnlAttributionEngine();

        // 1 contract = 100 shares. Vega = 25.0 per share (2500 per contract for 1.0 vol change = 100 vol pts)
        Valuation.PositionValuation opt0 = new Valuation.PositionValuation(
                "SPY261120C00100000", "OPTION", 1, 100, 4.00, "MID", 4.00, 0.0,
                0.20, "SVI", 0.50, 0.05, 25.0, -100.0, 0.0, false, null
        );
        Valuation v0 = new Valuation(T0, 100.0, "LIVE", List.of(opt0), 0.0, 0.0,
                50.0, 5.0, 2500.0, -100.0, 0.0, List.of());

        engine.update(v0);

        // Vol jumps by +0.02 (2.0 vol points, e.g. before earnings or Fed announcement)
        // Expected Vega PnL = 100 shares * 25.0 * 0.02 = $50.00
        double priceAfterShock = 4.00 + 0.50; // +$0.50 per share = +$50 total
        Valuation.PositionValuation opt1 = new Valuation.PositionValuation(
                "SPY261120C00100000", "OPTION", 1, 100, priceAfterShock, "MID", 4.00, 50.0,
                0.22, "SVI", 0.50, 0.05, 25.0, -100.0, 0.0, false, null
        );
        Valuation v1 = new Valuation(T0.plusSeconds(60), 100.0, "LIVE", List.of(opt1), 50.0, 0.0,
                50.0, 5.0, 2500.0, -100.0, 0.0, List.of());

        PnlAttribution attr = engine.update(v1);

        assertEquals(0.0, attr.spotChange(), 1e-9);
        assertEquals(50.0, attr.vegaPnl(), 1e-4, "Vega explains 100 * 25 * 0.02");
        assertEquals(0.0, attr.deltaPnl(), 1e-9);
        assertEquals(50.0, attr.totalPnlChange(), 1e-6);
        assertEquals(0.0, attr.residualPnl(), 0.01);
    }

    @Test
    void theHistoryIsBoundedButTheRunningTotalsAreNotAffectedByTrimmingIt() {
        // The valuation service feeds the engine every few seconds for as long as the process lives.
        PnlAttributionEngine engine = new PnlAttributionEngine();
        int updates = PnlAttributionEngine.MAX_HISTORY + 500;

        PnlAttribution latest = null;
        for (int i = 0; i < updates; i++) {
            latest = engine.update(new Valuation(T0.plusSeconds(5L * i), 100.0 + i, "LIVE", List.of(), 100.0 * i, 0.0,
                    100.0, 0.0, 0.0, 0.0, 0.0, List.of()));
        }

        assertEquals(PnlAttributionEngine.MAX_HISTORY, engine.history().size());
        assertEquals(100.0 * (updates - 1), latest.cumulativeDeltaPnl(), 1e-6, "totals keep counting past the trimmed history");
        assertEquals(100.0 * (updates - 1), engine.latest().cumulativeTotalPnl(), 1e-6);
    }

    @Test
    void multiStepCumulativeAttributionAddsUpCleanly() {
        PnlAttributionEngine engine = new PnlAttributionEngine();

        Valuation v0 = new Valuation(T0, 100.0, "LIVE", List.of(), 0.0, 0.0,
                100.0, 0.0, 0.0, 0.0, 0.0, List.of());
        engine.update(v0);

        // Step 1: spot +1.0
        Valuation v1 = new Valuation(T0.plusSeconds(60), 101.0, "LIVE", List.of(), 100.0, 0.0,
                100.0, 0.0, 0.0, 0.0, 0.0, List.of());
        engine.update(v1);

        // Step 2: spot +2.0
        Valuation v2 = new Valuation(T0.plusSeconds(120), 103.0, "LIVE", List.of(), 300.0, 0.0,
                100.0, 0.0, 0.0, 0.0, 0.0, List.of());
        PnlAttribution latest = engine.update(v2);

        assertEquals(300.0, latest.cumulativeTotalPnl(), 1e-6);
        assertEquals(300.0, latest.cumulativeDeltaPnl(), 1e-6);
        assertEquals(3, engine.history().size());

        // Reset
        engine.reset();
        assertEquals(0, engine.history().size());
        assertEquals(0.0, engine.latest().cumulativeTotalPnl(), 1e-6);
    }
}
