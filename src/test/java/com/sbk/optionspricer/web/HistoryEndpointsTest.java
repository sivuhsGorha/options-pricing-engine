package com.sbk.optionspricer.web;

import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.core.VolatilitySurfaceService;
import com.sbk.optionspricer.execution.PnlHistory;
import com.sbk.optionspricer.execution.Valuation;
import com.sbk.optionspricer.volatility.SurfaceHistory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The history endpoints serve what was recorded, say why when nothing was, and never emit NaN. */
class HistoryEndpointsTest {

    private static SurfaceHistory recordedSurface() throws Exception {
        SurfaceHistory history = new SurfaceHistory(Files.createTempDirectory("surface-api").resolve("surface_history.csv"));
        VolatilitySurfaceService service = new VolatilitySurfaceService(new SyntheticOptionChainProvider(100.0, 0.20, 0.05, 0.0),
                "SPY", 0.05, 0.0, Duration.ofMinutes(15), Clock.systemUTC(), chain -> { });
        service.refresh();
        history.record(service.latest().orElseThrow());
        return history;
    }

    @Test
    @SuppressWarnings("unchecked")
    void surfaceHistoryListsOnePointPerCalibrationOfTheRequestedModelInTheWindow() throws Exception {
        SurfaceHistory history = recordedSurface();

        Map<String, Object> body = OptionsDashboardServer.surfaceHistoryBody(history, "SVI", 24, Instant.now());

        assertEquals(true, body.get("ready"), body.toString());
        assertEquals("SVI", body.get("model"));
        assertEquals(1, body.get("count"));
        List<Map<String, Object>> points = (List<Map<String, Object>>) body.get("points");
        assertEquals(1, points.size());
        Map<String, Object> p = points.get(0);
        assertEquals(0.20, (Double) p.get("atmVol"), 0.01);
        assertNotNull(p.get("skew"));
        assertEquals(false, p.get("marketData"), "a synthetic calibration is labelled as such in the history too");
        assertEquals("SYNTHETIC", p.get("source"));
        assertTrue(((Number) p.get("quotesUsed")).intValue() > 0);
        assertEquals("SPY", body.get("symbol"));
        String json = Json.write(body);
        assertFalse(json.contains("NaN"), json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void surfaceHistorySaysWhyWhenNothingFallsInTheWindowOrNothingIsConfigured() throws Exception {
        SurfaceHistory history = recordedSurface();

        Map<String, Object> later = OptionsDashboardServer.surfaceHistoryBody(history, "SVI", 24, Instant.now().plus(Duration.ofHours(25)));
        assertEquals(false, later.get("ready"));
        assertTrue(later.get("status").toString().contains("no SVI calibration"), later.get("status").toString());
        assertTrue(((List<Object>) later.get("points")).isEmpty());

        Map<String, Object> none = OptionsDashboardServer.surfaceHistoryBody(null, "SSVI", 24, Instant.now());
        assertEquals(false, none.get("ready"));
        assertTrue(none.get("status").toString().contains("configured"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pnlReportsTheTotalsAndTheDayFromTheSampledRecord() throws Exception {
        PnlHistory history = new PnlHistory(Files.createTempDirectory("pnl-api").resolve("pnl_history.csv"), Duration.ZERO);
        history.record(valuation("2026-10-07T20:00:00Z", 100.0, 0.0));
        history.record(valuation("2026-10-08T13:35:00Z", 100.0, 20.0));
        history.record(valuation("2026-10-08T14:00:00Z", 100.0, -10.0));

        Map<String, Object> body = OptionsDashboardServer.pnlBody(history, Instant.parse("2026-10-08T15:00:00Z"));

        assertEquals(true, body.get("ready"));
        assertEquals(100.0, body.get("realizedPnl"));
        assertEquals(-10.0, body.get("unrealizedPnl"));
        assertEquals(90.0, body.get("totalPnl"));
        Map<String, Object> day = (Map<String, Object>) body.get("day");
        assertEquals("2026-10-08", day.get("date"));
        assertEquals("America/New_York", day.get("timezone"));
        assertEquals(true, day.get("baselineIsPreviousClose"));
        assertEquals(100.0, day.get("baseline"));
        assertEquals(-10.0, day.get("pnl"));
        assertEquals(30.0, day.get("maxDrawdown"));
        assertEquals(2, day.get("sampleCount"));
        List<Map<String, Object>> samples = (List<Map<String, Object>>) day.get("samples");
        assertEquals(2, samples.size());
        assertEquals(90.0, samples.get(1).get("total"));
        assertFalse(Json.write(body).contains("NaN"));

        Map<String, Object> empty = OptionsDashboardServer.pnlBody(new PnlHistory(Files.createTempDirectory("pnl-api-empty").resolve("p.csv"), Duration.ZERO), Instant.now());
        assertEquals(false, empty.get("ready"));
        assertTrue(empty.get("status").toString().contains("no valuation"));
        assertEquals(false, OptionsDashboardServer.pnlBody(null, Instant.now()).get("ready"));
    }

    @Test
    void longSeriesAreThinnedButKeepTheirLastPoint() {
        List<Integer> series = new ArrayList<>();
        for (int i = 0; i < 1000; i++) series.add(i);

        List<Integer> thinned = OptionsDashboardServer.thin(series, 400);

        assertTrue(thinned.size() <= 401, "" + thinned.size());
        assertEquals(0, thinned.get(0));
        assertEquals(999, thinned.get(thinned.size() - 1));
        assertSame(series, OptionsDashboardServer.thin(series, 1000), "a short series is returned as is");
    }

    private static Valuation valuation(String at, double realized, double unrealized) {
        return new Valuation(Instant.parse(at), 777.0, "TEST/LIVE", List.of(), unrealized, realized, 0, 0, 0, 0, 0, List.of());
    }
}
