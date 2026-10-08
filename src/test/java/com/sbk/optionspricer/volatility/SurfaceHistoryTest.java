package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.core.VolatilitySurfaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Every calibration leaves a row per model that comes back the same after a restart, with the front ATM vol and skew. */
class SurfaceHistoryTest {

    private static Path temp() throws Exception {
        return Files.createTempDirectory("surface-history").resolve("surface_history.csv");
    }

    private static VolatilitySurfaceSource.Snapshot calibrated() {
        VolatilitySurfaceService service = new VolatilitySurfaceService(new SyntheticOptionChainProvider(100.0, 0.20, 0.05, 0.0),
                "SPY", 0.05, 0.0, Duration.ofMinutes(15), Clock.systemUTC(), chain -> { });
        service.refresh();
        return service.latest().orElseThrow();
    }

    @Test
    void aCalibrationWritesOneRowPerModelWithTheFrontAtmVolAndSkewReadOffTheGrid() throws Exception {
        SurfaceHistory history = new SurfaceHistory(temp());
        VolatilitySurfaceSource.Snapshot snapshot = calibrated();

        List<SurfaceHistory.Entry> written = history.record(snapshot);

        assertEquals(3, written.size());
        assertEquals(List.of("SSVI", "SVI", "SABR"), written.stream().map(SurfaceHistory.Entry::model).toList());
        for (SurfaceHistory.Entry e : written) {
            SurfaceFitter.Fit fit = switch (e.model()) { case "SSVI" -> snapshot.ssvi(); case "SVI" -> snapshot.svi(); default -> snapshot.sabr(); };
            double front = java.util.Arrays.stream(fit.fittedExpiries()).min().orElseThrow();
            assertEquals(front, e.frontExpiry(), 1e-12);
            assertEquals(SurfaceFitter.volAt(fit, front, 100.0, 0.5).orElseThrow(), e.atmVol(), 1e-12, "ATM vol is the grid at strike = spot");
            assertEquals(0.20, e.atmVol(), 0.01, e.model() + " fitted to a flat 20% chain");
            assertTrue(Math.abs(e.skew()) < 0.03, e.model() + " skew on a flat chain should be near zero: " + e.skew());
            assertEquals(fit.rmse(), e.rmse(), 0.0);
            assertEquals(fit.quotesUsed(), e.quotesUsed());
            assertEquals("SYNTHETIC", e.source());
            assertFalse(e.marketData(), "a generated chain is never market data, in the history either");
            assertEquals(fit.parameters(), e.parameters());
        }
        assertEquals(4, Files.readAllLines(history.path()).size(), "header plus three rows");
    }

    @Test
    void theHistorySurvivesARestartAndCanBeQueriedByModelAndTime() throws Exception {
        Path file = temp();
        SurfaceHistory first = new SurfaceHistory(file);
        VolatilitySurfaceSource.Snapshot snapshot = calibrated();
        List<SurfaceHistory.Entry> written = first.record(snapshot);

        SurfaceHistory reopened = new SurfaceHistory(file);

        assertEquals(3, reopened.size());
        assertEquals(written, reopened.since(null, null), "what was written is what comes back, parameters included");
        assertEquals(1, reopened.since(null, "SVI").size());
        assertTrue(reopened.since(snapshot.asOf().plusSeconds(1), null).isEmpty(), "nothing after the calibration");
        assertEquals(3, reopened.since(snapshot.asOf(), null).size(), "the window is inclusive of its start");
    }

    @Test
    void aSnapshotWithoutAFitForAModelRecordsOnlyTheModelsThatFitted() throws Exception {
        SurfaceHistory history = new SurfaceHistory(temp());
        VolatilitySurfaceSource.Snapshot full = calibrated();
        VolatilitySurfaceSource.Snapshot partial = new VolatilitySurfaceSource.Snapshot(full.asOf(), full.symbol(), full.source(), full.marketData(),
                full.spot(), null, full.sabr(), null, full.quotesSkipped(), full.warnings());

        assertEquals(List.of("SABR"), history.record(partial).stream().map(SurfaceHistory.Entry::model).toList());
    }

    @Test
    void aCorruptRowStopsTheLoadNamingTheLine() throws Exception {
        Path file = temp();
        new SurfaceHistory(file).record(calibrated());
        Files.writeString(file, "2026-10-08T10:00:00Z,SPY,CBOE,true,777,SSVI,nine,0.01,0.08,0.2,0.01,\n",
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SurfaceHistory(file));

        assertTrue(e.getMessage().contains("line 5"), e.getMessage());
    }

    @Test
    void theSurfaceServiceHandsEachCalibrationToItsListener() throws Exception {
        SurfaceHistory history = new SurfaceHistory(temp());
        VolatilitySurfaceService service = new VolatilitySurfaceService(new SyntheticOptionChainProvider(100.0, 0.20, 0.05, 0.0),
                "SPY", 0.05, 0.0, Duration.ofMinutes(15), Clock.systemUTC(), chain -> { });
        service.setSnapshotListener(history);

        service.refresh();
        service.refresh();

        assertEquals(6, history.size(), "two calibrations, three models each");
        Instant asOf = service.latest().orElseThrow().asOf();
        assertEquals(asOf, history.since(null, "SSVI").get(1).at());
    }
}
