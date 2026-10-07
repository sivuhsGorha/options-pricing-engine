package com.sbk.optionspricer.market;

import com.sbk.optionspricer.execution.ExecutionResult;
import com.sbk.optionspricer.execution.Order;
import com.sbk.optionspricer.execution.PaperTradingExecutionAdapter;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.FillRecorder;
import com.sbk.optionspricer.risk.LiquidityRiskMonitor;
import com.sbk.optionspricer.web.LiveSpotProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A field the provider did not supply is reported as unknown, never filled with a placeholder, and every
 * consumer of that field copes with "unknown" instead of trading on an invented number.
 */
class MarketDataHonestyTest {

    private static final Instant NOW = Instant.now();

    // ---------------- the snapshot itself ----------------

    @Test
    void aSnapshotMayHaveNoBookAndNoVolume() {
        MarketSnapshot snapshot = new MarketSnapshot("SPY", Double.NaN, Double.NaN, 100.0,
                MarketSnapshot.VOLUME_UNKNOWN, NOW, NOW, 0L, "FINNHUB", MarketDataStatus.DELAYED);

        assertFalse(snapshot.hasBook());
        assertFalse(snapshot.hasVolume());
        assertEquals(100.0, snapshot.last());
    }

    @Test
    void aSnapshotWithABookAndVolumeReportsBoth() {
        MarketSnapshot snapshot = new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 4321L, NOW, NOW, 0L, "POLYGON", MarketDataStatus.LIVE);

        assertTrue(snapshot.hasBook());
        assertTrue(snapshot.hasVolume());
    }

    @Test
    void halfABookOrAnImpossibleVolumeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot("SPY", 99.99, Double.NaN, 100.0, 10L, NOW, NOW, 0L, "X", MarketDataStatus.LIVE));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot("SPY", Double.NaN, 100.01, 100.0, 10L, NOW, NOW, 0L, "X", MarketDataStatus.LIVE));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot("SPY", 99.99, 100.01, 100.0, -2L, NOW, NOW, 0L, "X", MarketDataStatus.LIVE));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot("SPY", 100.01, 99.99, 100.0, 10L, NOW, NOW, 0L, "X", MarketDataStatus.LIVE));
    }

    // ---------------- the adapter passes "unknown" through ----------------

    @Test
    void aLastPriceOnlyProviderYieldsASnapshotWithoutABookOrVolume() {
        long ts = System.currentTimeMillis() / 1000 - 2;
        LiveSpotProvider finnhub = new LiveSpotProvider("fh", null, null, null, (url, headers) -> "{\"c\":101.25,\"t\":" + ts + "}");

        MarketSnapshot snapshot = new LiveMarketSnapshotAdapter(finnhub, 0.01d).getSnapshot("SPY");

        assertEquals(101.25, snapshot.last(), 1e-9);
        assertFalse(snapshot.hasBook(), "Finnhub /quote has no bid/ask; none may be invented");
        assertFalse(snapshot.hasVolume(), "Finnhub /quote has no volume; none may be invented");
        assertEquals(MarketDataStatus.DELAYED, snapshot.status());
    }

    @Test
    void aBookProviderYieldsItsRealBookAndVolume() {
        LiveSpotProvider polygon = new LiveSpotProvider(null, "pk", null, null, (url, headers) ->
                "{\"ticker\":{\"lastQuote\":{\"p\":123.45,\"P\":123.55,\"t\":" + System.currentTimeMillis() + "},"
                        + "\"lastTrade\":{\"p\":123.50},\"min\":{\"v\":4321}}}");

        MarketSnapshot snapshot = new LiveMarketSnapshotAdapter(polygon, 0.01d).getSnapshot("SPY");

        assertEquals(123.45, snapshot.bid(), 1e-9);
        assertEquals(123.55, snapshot.ask(), 1e-9);
        assertEquals(4321L, snapshot.volume());
    }

    @Test
    void theSimulatedFallbackDoesNotClaimAVolume() {
        LiveSpotProvider noKeys = new LiveSpotProvider(null, null, null, null, (url, headers) -> "{}");

        MarketSnapshot snapshot = new LiveMarketSnapshotAdapter(noKeys, 100.0, 0.01d).getSnapshot("SPY");

        assertEquals(MarketDataStatus.SIMULATED, snapshot.status());
        assertFalse(snapshot.hasVolume());
    }

    // ---------------- the liquidity gate judges only what it knows ----------------

    private static PreTradeRiskFilter filterWithLiquidity() {
        return new PreTradeRiskFilter(1_000, 1e9, 1_000, Map.of(1, "SPY"),
                new ConcentrationLimitManager(Map.of("SPY", 1e9)), new LiquidityRiskMonitor(50.0, 1_000L));
    }

    @Test
    void unknownBookAndVolumeDoNotFailTheLiquidityCheck() {
        assertTrue(filterWithLiquidity().checkRisk(new Order(1, true, 5, 100.0), "SPY", Double.NaN, Double.NaN, MarketSnapshot.VOLUME_UNKNOWN, 1),
                "absence of data is not evidence of an illiquid market");
    }

    @Test
    void aKnownThinVolumeFailsEvenWithoutABook() {
        assertFalse(filterWithLiquidity().checkRisk(new Order(1, true, 5, 100.0), "SPY", Double.NaN, Double.NaN, 10L, 1),
                "volume 10 against a minimum of 1,000 must be rejected whether or not a book exists");
    }

    @Test
    void aKnownWideSpreadFailsEvenWithUnknownVolume() {
        assertFalse(filterWithLiquidity().checkRisk(new Order(1, true, 5, 100.0), "SPY", 100.0, 101.0, MarketSnapshot.VOLUME_UNKNOWN, 1),
                "a 100 bps spread against a 50 bps limit must be rejected whether or not volume is known");
        assertTrue(filterWithLiquidity().checkRisk(new Order(1, true, 5, 100.0), "SPY", 100.0, 100.01, MarketSnapshot.VOLUME_UNKNOWN, 1));
    }

    // ---------------- paper fills without a book use the order's own price ----------------

    @Test
    void paperFillWithoutABookIsAtTheOrderPriceNotAtAHardCodedHundred() {
        PaperTradingExecutionAdapter adapter = new PaperTradingExecutionAdapter(new PositionTracker(FillRecorder.NONE), 25.0);

        ExecutionResult buy = adapter.executeOrder(new Order(1, true, 5, 480.0), "SPY", Double.NaN, Double.NaN);
        ExecutionResult sell = adapter.executeOrder(new Order(2, false, 5, 480.0), "SPY", Double.NaN, Double.NaN);

        assertEquals(480.0 * (1 + 0.0025), buy.executionPrice(), 1e-9);
        assertEquals(480.0 * (1 - 0.0025), sell.executionPrice(), 1e-9);
    }
}
