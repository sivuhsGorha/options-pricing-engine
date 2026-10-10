package com.sbk.optionspricer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbk.optionspricer.execution.ExecutionResult;
import com.sbk.optionspricer.execution.Order;
import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.market.MarketSnapshotAdapter;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** API responses must be valid JSON, report failures as errors, and never leak internals. */
class ApiResponseHardeningTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OptionsDashboardServer server;
    private Path webRoot;

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.stop();
        if (webRoot != null) Files.deleteIfExists(webRoot);
    }

    private void start(MmapStateReader reader, OrderManager orderManager, PositionTracker tracker, MarketSnapshotAdapter adapter) throws Exception {
        start(reader, orderManager, tracker, adapter, null);
    }

    private void start(MmapStateReader reader, OrderManager orderManager, PositionTracker tracker, MarketSnapshotAdapter adapter,
                       com.sbk.optionspricer.volatility.VolatilitySurfaceSource surfaceSource) throws Exception {
        start(reader, orderManager, tracker, adapter, surfaceSource, null, null);
    }

    private void start(MmapStateReader reader, OrderManager orderManager, PositionTracker tracker, MarketSnapshotAdapter adapter,
                       com.sbk.optionspricer.volatility.VolatilitySurfaceSource surfaceSource,
                       java.util.function.Supplier<java.util.Map<String, String>> feedStatus,
                       com.sbk.optionspricer.execution.OperatorControls controls) throws Exception {
        start(reader, orderManager, tracker, adapter, surfaceSource, feedStatus, controls, null);
    }

    private void start(MmapStateReader reader, OrderManager orderManager, PositionTracker tracker, MarketSnapshotAdapter adapter,
                       com.sbk.optionspricer.volatility.VolatilitySurfaceSource surfaceSource,
                       java.util.function.Supplier<java.util.Map<String, String>> feedStatus,
                       com.sbk.optionspricer.execution.OperatorControls controls,
                       com.sbk.optionspricer.execution.ValuationSource valuationSource) throws Exception {
        webRoot = Files.createTempDirectory("api-hardening-web");
        server = new OptionsDashboardServer(OptionsDashboardServerTest.TEST_SECRET, OptionsDashboardServerTest.OPERATOR_PASSWORD,
                OptionsDashboardServerTest.ALLOWED_ORIGIN, "127.0.0.1", 0, 0, reader, webRoot.toString(),
                orderManager, tracker, adapter);
        if (surfaceSource != null) {
            server.setSurfaceSource(surfaceSource);
        }
        if (feedStatus != null) {
            server.setFeedStatusSource(feedStatus);
        }
        if (controls != null) {
            server.setOperatorControls(controls);
        }
        if (valuationSource != null) {
            server.setValuationSource(valuationSource);
        }
        server.start();
        OptionsDashboardServerTest.waitForWebSocketPort(server);
    }

    private static com.sbk.optionspricer.execution.OperatorControls fixedControls(boolean halted) {
        var state = new com.sbk.optionspricer.execution.OperatorControls.ControlState(halted, halted ? "test" : null,
                halted ? Instant.now() : null, true, "SPY", 0.001, 10, "momentum", null);
        return new com.sbk.optionspricer.execution.OperatorControls() {
            @Override public ControlState state() { return state; }
            @Override public ControlState halt(String reason) { return state; }
            @Override public ControlState resume() { return state; }
            @Override public ControlState setStrategyEnabled(boolean enabled) { return state; }
        };
    }

    @Test
    void healthReportsEveryComponentAndTheProvidersWithoutProbing() throws Exception {
        MarketSnapshotAdapter live = symbol -> new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "POLYGON", MarketDataStatus.LIVE);
        var loading = new com.sbk.optionspricer.volatility.VolatilitySurfaceSource.Status(
                com.sbk.optionspricer.volatility.VolatilitySurfaceSource.State.LOADING, "calibration has not run yet", Instant.now());
        java.util.concurrent.atomic.AtomicInteger statusReads = new java.util.concurrent.atomic.AtomicInteger();
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, live, surfaceSource(null, loading),
                () -> { statusReads.incrementAndGet(); return java.util.Map.of("FINNHUB", "LIVE", "POLYGON", "UNAVAILABLE"); }, fixedControls(false));

        JsonNode body = MAPPER.readTree(get("/api/health").body());

        assertEquals("ok", body.get("status").asText(), body.toString());
        assertEquals("LIVE", body.get("providers").get("FINNHUB").asText(), "the header badges read this map");
        assertEquals("UNAVAILABLE", body.get("providers").get("POLYGON").asText());
        assertEquals("LIVE", body.get("components").get("marketData").get("status").asText());
        assertEquals("READY", body.get("components").get("riskState").get("status").asText());
        assertEquals("LOADING", body.get("components").get("surface").get("status").asText());
        assertFalse(body.get("components").get("trading").get("halted").asBoolean());
        assertTrue(body.get("uptimeSeconds").asLong() >= 0);
        assertEquals(1, statusReads.get(), "one cheap status read per request, no provider probe");
        // the original fields are still present for older clients
        assertEquals("LIVE", body.get("sourceStatus").asText());
        assertTrue(body.get("tradable").asBoolean());
    }

    @Test
    void healthIsDownWhenTheRiskStateCannotBeReadAndDegradedWhenTradingIsHalted() throws Exception {
        start(reader(() -> { throw new IllegalStateException("UNAVAILABLE"); }), null, null, null, null, null, fixedControls(false));
        JsonNode down = MAPPER.readTree(get("/api/health").body());
        assertEquals("down", down.get("status").asText(), down.toString());
        assertEquals("UNAVAILABLE", down.get("components").get("riskState").get("status").asText());
        server.stop();

        MarketSnapshotAdapter live = symbol -> new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "POLYGON", MarketDataStatus.LIVE);
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, live, null, null, fixedControls(true));
        JsonNode halted = MAPPER.readTree(get("/api/health").body());
        assertEquals("degraded", halted.get("status").asText(), halted.toString());
        assertTrue(halted.get("components").get("trading").get("halted").asBoolean());
        assertEquals("DEMO", halted.get("components").get("surface").get("status").asText(), "no calibration service wired in");
    }

    private static com.sbk.optionspricer.volatility.VolatilitySurfaceSource surfaceSource(
            com.sbk.optionspricer.volatility.VolatilitySurfaceSource.Snapshot snapshot,
            com.sbk.optionspricer.volatility.VolatilitySurfaceSource.Status status) {
        return new com.sbk.optionspricer.volatility.VolatilitySurfaceSource() {
            @Override
            public java.util.Optional<Snapshot> latest() {
                return java.util.Optional.ofNullable(snapshot);
            }

            @Override
            public Status status() {
                return status;
            }
        };
    }

    private static MmapStateReader reader(java.util.function.Supplier<MmapStateReader.RiskState> state) {
        return new MmapStateReader(true) {
            @Override
            public RiskState readState() {
                return state.get();
            }

            @Override
            public void close() {}
        };
    }

    private String cookie() throws Exception {
        HttpRequest login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/login"))
                .header("Origin", OptionsDashboardServerTest.ALLOWED_ORIGIN)
                .POST(HttpRequest.BodyPublishers.ofString(OptionsDashboardServerTest.OPERATOR_PASSWORD, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
                .header("Origin", OptionsDashboardServerTest.ALLOWED_ORIGIN)
                .header("Cookie", cookie())
                .GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void executionEndpointEscapesTextThatComesFromRejectionReasons() throws Exception {
        String hostile = "bad \"quote\" \\ back\nslash <script>";
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, 0, order.price(), false, hostile), tracker);
        manager.submit(new Order(1, true, 5, 100.0), new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 2000L,
                Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE));
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), manager, tracker, null);

        HttpResponse<String> response = get("/api/execution");

        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body()); // fails if the body is not valid JSON
        assertEquals(hostile, body.get(0).get("rejectionReason").asText());
    }

    @Test
    void positionsEndpointReportsPositionsAndTheTradingHalt() throws Exception {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), tracker);
        manager.submit(new Order(1, true, 7, 100.0), new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 2000L,
                Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE));
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), manager, tracker, null);

        var call = new com.sbk.optionspricer.instruments.Instrument("SPY", LocalDate.of(2026, 11, 20), 780.0,
                com.sbk.optionspricer.OptionType.CALL, 100.0, null, 0.01, true);
        manager.submit(new Order(2, true, 3, 9.9), new MarketSnapshot(call.contractSymbol(), 9.8, 10.0, 9.9, 500L,
                Instant.now(), Instant.now(), 0L, "CBOE_DELAYED", MarketDataStatus.DELAYED, call));

        JsonNode flat = MAPPER.readTree(get("/api/positions").body());
        assertFalse(flat.get("halted").asBoolean());
        JsonNode shares = null, contract = null;
        for (JsonNode row : flat.get("positions")) {
            if ("SPY".equals(row.get("symbol").asText())) shares = row;
            if ("SPY261120C00780000".equals(row.get("symbol").asText())) contract = row;
        }
        assertNotNull(shares);
        assertEquals(7, shares.get("quantity").asInt());
        assertEquals(1, shares.get("multiplier").asInt());
        assertEquals("STOCK", shares.get("kind").asText());
        assertNotNull(contract, "the option position is listed under its contract symbol");
        assertEquals("OPTION", contract.get("kind").asText());
        assertEquals("SPY", contract.get("underlying").asText());
        assertEquals("2026-11-20", contract.get("expiry").asText());
        assertEquals(780.0, contract.get("strike").asDouble(), 1e-9);
        assertEquals("CALL", contract.get("type").asText());
        assertEquals(100, contract.get("multiplier").asInt());

        manager.getTradingHalt().halt("test halt \"quoted\"");
        JsonNode halted = MAPPER.readTree(get("/api/positions").body());
        assertTrue(halted.get("halted").asBoolean());
        assertEquals("test halt \"quoted\"", halted.get("haltReason").asText());
    }

    @Test
    void spotEndpointReportsUnknownBookAndVolumeAsNullNotAsNumbers() throws Exception {
        MarketSnapshotAdapter noBook = symbol -> new MarketSnapshot("SPY", Double.NaN, Double.NaN, 480.25,
                MarketSnapshot.VOLUME_UNKNOWN, Instant.now(), Instant.now(), 0L, "FINNHUB", MarketDataStatus.DELAYED);
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, noBook);

        JsonNode body = MAPPER.readTree(get("/api/spot").body());

        assertEquals(480.25, body.get("spotPrice").asDouble(), 1e-9);
        assertTrue(body.get("bid").isNull(), "no book: bid must be null, not a pretend price");
        assertTrue(body.get("ask").isNull());
        assertTrue(body.get("volume").isNull(), "no volume: null, not 2000");
    }

    // Found on review (2026-10-10): the spot, health and risk endpoints asked for "SPY" whatever execution.symbol said,
    // so with another symbol the header showed one instrument while the book traded another.

    @Test
    void spotHealthAndRiskFollowTheConfiguredSymbolNotAHardCodedOne() throws Exception {
        java.util.List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        MarketSnapshotAdapter adapter = symbol -> {
            asked.add(symbol);
            return new MarketSnapshot(symbol, Double.NaN, Double.NaN, 450.0, MarketSnapshot.VOLUME_UNKNOWN,
                    Instant.now(), Instant.now(), 0L, "FINNHUB", MarketDataStatus.DELAYED);
        };
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, adapter);
        server.setSymbol("qqq");

        assertEquals("QQQ", MAPPER.readTree(get("/api/spot").body()).get("symbol").asText());
        assertEquals("QQQ", MAPPER.readTree(get("/api/health").body()).get("symbol").asText());
        get("/api/risk");

        assertFalse(asked.isEmpty());
        assertTrue(asked.stream().allMatch("QQQ"::equals), "every quote request names the configured symbol: " + asked);
    }

    @Test
    void spotEndpointReportsARealBookAndVolumeWhenTheProviderHasThem() throws Exception {
        MarketSnapshotAdapter book = symbol -> new MarketSnapshot("SPY", 480.20, 480.30, 480.25,
                4321L, Instant.now(), Instant.now(), 0L, "POLYGON", MarketDataStatus.LIVE);
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, book);

        JsonNode body = MAPPER.readTree(get("/api/spot").body());

        assertEquals(480.20, body.get("bid").asDouble(), 1e-9);
        assertEquals(480.30, body.get("ask").asDouble(), 1e-9);
        assertEquals(4321L, body.get("volume").asLong());
    }

    @Test
    void valuationEndpointReportsMarksGreeksAndPnlWithTheirSourcesOrSaysWhyNot() throws Exception {
        var row = new com.sbk.optionspricer.execution.Valuation.PositionValuation("SPY261120C00780000", "OPTION", 2, 100, 9.9, "MID",
                2.0, 1580.0, 0.21, "SVI", 0.52, 0.012, 0.95, -5.1, 0.3, false, null);
        var unvalued = new com.sbk.optionspricer.execution.Valuation.PositionValuation("SPY281215C00100000", "OPTION", 1, 100, Double.NaN, "NONE",
                5.0, Double.NaN, Double.NaN, "NONE", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, null);
        var valuation = new com.sbk.optionspricer.execution.Valuation(Instant.now(), 780.25, "FINNHUB/DELAYED", java.util.List.of(row, unvalued),
                1580.0, 75.0, 2 * 100 * 0.52, 2 * 100 * 0.012, 2 * 100 * 0.95, 2 * 100 * -5.1, 2 * 100 * 0.3, java.util.List.of("one warning"));
        com.sbk.optionspricer.execution.ValuationSource ready = new com.sbk.optionspricer.execution.ValuationSource() {
            @Override public java.util.Optional<com.sbk.optionspricer.execution.Valuation> latestValuation() { return java.util.Optional.of(valuation); }
            @Override public String valuationStatus() { return ""; }
        };
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, null, null, null, null, ready);

        JsonNode body = MAPPER.readTree(get("/api/valuation").body());

        assertTrue(body.get("ready").asBoolean());
        assertEquals("FINNHUB/DELAYED", body.get("spotSource").asText());
        assertEquals(1580.0, body.get("unrealizedPnl").asDouble(), 1e-9);
        assertEquals(75.0, body.get("realizedPnl").asDouble(), 1e-9);
        assertEquals(2 * 100 * -5.1, body.get("netTheta").asDouble(), 1e-6);
        JsonNode first = body.get("positions").get(0);
        assertEquals("MID", first.get("markSource").asText());
        assertEquals("SVI", first.get("volSource").asText());
        assertEquals(0.52, first.get("delta").asDouble(), 1e-9);
        JsonNode second = body.get("positions").get(1);
        assertTrue(second.get("mark").isNull(), "an unvalued contract has null, never 0 or NaN in the JSON");
        assertTrue(second.get("delta").isNull());
        server.stop();

        com.sbk.optionspricer.execution.ValuationSource notYet = new com.sbk.optionspricer.execution.ValuationSource() {
            @Override public java.util.Optional<com.sbk.optionspricer.execution.Valuation> latestValuation() { return java.util.Optional.empty(); }
            @Override public String valuationStatus() { return "no spot price: cannot mark the book"; }
        };
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, null, null, null, null, notYet);
        JsonNode pending = MAPPER.readTree(get("/api/valuation").body());
        assertFalse(pending.get("ready").asBoolean());
        assertTrue(pending.get("status").asText().contains("no spot"));
    }

    @Test
    void positionsEndpointWithoutAnExecutionStackIsEmptyNotAnError() throws Exception {
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, null);

        JsonNode body = MAPPER.readTree(get("/api/positions").body());

        assertFalse(body.get("halted").asBoolean());
        assertEquals(0, body.get("positions").size());
    }

    @Test
    void riskEndpointReportsUnavailableStateAsAnErrorNotAsZeroRisk() throws Exception {
        start(reader(() -> { throw new IllegalStateException("UNAVAILABLE"); }), null, null, null);

        HttpResponse<String> response = get("/api/risk");

        assertEquals(503, response.statusCode(), "monitoring must not read 'no risk' while the risk engine is down");
        JsonNode body = MAPPER.readTree(response.body());
        assertTrue(body.has("error"));
        assertFalse(body.has("netDelta"), "no fabricated zero values");
    }

    @Test
    void unexpectedFailureReturns500WithoutLeakingDetails() throws Exception {
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null,
                symbol -> { throw new RuntimeException("db password is hunter2 at /internal/path"); });

        HttpResponse<String> response = get("/api/spot");

        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("hunter2"), response.body());
        assertFalse(response.body().contains("/internal/path"), response.body());
        assertTrue(MAPPER.readTree(response.body()).has("error"));
    }

    @Test
    void spotEndpointDoesNotReportAPriceWhenTheSourceIsUnavailable() throws Exception {
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null,
                symbol -> new MarketSnapshot("SPY", 99.5, 100.5, 100.0, 0L, Instant.now(), Instant.EPOCH,
                        Long.MAX_VALUE / 4, "SIMULATED", MarketDataStatus.UNAVAILABLE));

        JsonNode body = MAPPER.readTree(get("/api/spot").body());

        assertEquals("UNAVAILABLE", body.get("status").asText());
        assertTrue(body.get("spotPrice").isNull(), "a placeholder price must not be shown as the spot: " + body);
    }

    @Test
    void surfaceModelParameterIsParsedExactlyNotBySubstring() throws Exception {
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, null);

        assertEquals("SSVI", MAPPER.readTree(get("/api/surface3d?model=SSVI").body()).get("model").asText());
        assertEquals("SABR", MAPPER.readTree(get("/api/surface3d?model=FREE_SABR").body()).get("model").asText(),
                "FREE_SABR was an alias with no free-boundary correction behind it; it is served as SABR");
        assertEquals("SABR", MAPPER.readTree(get("/api/surface3d?model=SSVIX").body()).get("model").asText(),
                "a longer, unknown value must not match as a prefix");
        assertEquals("SABR", MAPPER.readTree(get("/api/surface3d?x=model%3DSSVI").body()).get("model").asText());
        JsonNode surface = MAPPER.readTree(get("/api/surface3d?model=SSVI").body());
        assertEquals(9, surface.get("x").size());
        assertEquals(9, surface.get("z").size());
        assertEquals(9, surface.get("z").get(0).size());
    }

    @Test
    void surfaceWithoutACalibrationServiceIsLabelledAsADemo() throws Exception {
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, null);

        JsonNode body = MAPPER.readTree(get("/api/surface3d?model=SSVI").body());

        assertTrue(body.get("ready").asBoolean());
        assertTrue(body.get("demo").asBoolean(), "fixed parameters at spot 100 are a demonstration and must say so");
        assertEquals("DEMO", body.get("source").asText());
    }

    @Test
    void surfaceWhileCalibratingReportsTheStatusInsteadOfNumbers() throws Exception {
        var loading = new com.sbk.optionspricer.volatility.VolatilitySurfaceSource.Status(
                com.sbk.optionspricer.volatility.VolatilitySurfaceSource.State.LOADING, "calibration has not run yet", Instant.now());
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, null, surfaceSource(null, loading));

        JsonNode body = MAPPER.readTree(get("/api/surface3d?model=SSVI").body());

        assertFalse(body.get("ready").asBoolean());
        assertEquals("LOADING", body.get("status").asText());
        assertFalse(body.has("z"), "no surface may be shown before one has been fitted");
    }

    @Test
    void aFittedSurfaceCarriesItsProvenanceFitQualityAndTheQuotesItWasFittedTo() throws Exception {
        LocalDate today = LocalDate.now();
        var synthetic = new com.sbk.optionspricer.SyntheticOptionChainProvider(100.0, 0.2, 0.05, 0.0);
        java.util.List<com.sbk.optionspricer.OptionChain> chains = new java.util.ArrayList<>();
        for (LocalDate expiry : com.sbk.optionspricer.OptionChainProvider.thirdFridays(today, 3)) chains.add(synthetic.getOptionChain("SPY", expiry));
        var extraction = com.sbk.optionspricer.volatility.SurfaceFitter.extractPoints(chains, 0.05, 0.0, today);
        var snapshot = new com.sbk.optionspricer.volatility.VolatilitySurfaceSource.Snapshot(Instant.now(), "SPY", "SYNTHETIC", false, 100.0,
                com.sbk.optionspricer.volatility.SurfaceFitter.fitSsvi(extraction.points()),
                com.sbk.optionspricer.volatility.SurfaceFitter.fitSabr(extraction.points()),
                com.sbk.optionspricer.volatility.SurfaceFitter.fitSvi(extraction.points()), extraction.quotesSkipped(), java.util.List.of());
        var ready = new com.sbk.optionspricer.volatility.VolatilitySurfaceSource.Status(
                com.sbk.optionspricer.volatility.VolatilitySurfaceSource.State.READY, "ok", Instant.now());
        start(reader(() -> new MmapStateReader.RiskState(0, 0, 0, 0)), null, null, null, surfaceSource(snapshot, ready));

        JsonNode ssvi = MAPPER.readTree(get("/api/surface3d?model=SSVI").body());
        assertTrue(ssvi.get("ready").asBoolean());
        assertEquals("SYNTHETIC", ssvi.get("source").asText());
        assertTrue(ssvi.get("demo").asBoolean(), "a synthetic chain is not market data");
        assertTrue(ssvi.get("rmse").asDouble() >= 0.0);
        assertTrue(ssvi.get("points").size() > 0, "the quotes the surface was fitted to are returned for display");
        assertEquals(ssvi.get("y").size(), ssvi.get("z").size());
        assertEquals(ssvi.get("x").size(), ssvi.get("z").get(0).size());
        assertTrue(ssvi.get("parameters").has("eta"));

        JsonNode sabr = MAPPER.readTree(get("/api/surface3d?model=SABR").body());
        assertEquals("SABR", sabr.get("model").asText());
        assertTrue(sabr.get("parameters").has("beta"));

        JsonNode svi = MAPPER.readTree(get("/api/surface3d?model=SVI").body());
        assertEquals("SVI", svi.get("model").asText());
        assertTrue(svi.get("ready").asBoolean());
        assertTrue(svi.get("parameters").fieldNames().next().startsWith("a["), "five raw-SVI parameters per expiry");
        assertFalse(svi.get("noArbitrageConditionsHold").asBoolean(), "raw SVI must not claim a guarantee it lacks");
    }
}
