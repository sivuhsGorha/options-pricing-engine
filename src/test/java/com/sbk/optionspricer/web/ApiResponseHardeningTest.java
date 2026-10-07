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
        webRoot = Files.createTempDirectory("api-hardening-web");
        server = new OptionsDashboardServer(OptionsDashboardServerTest.TEST_SECRET, OptionsDashboardServerTest.OPERATOR_PASSWORD,
                OptionsDashboardServerTest.ALLOWED_ORIGIN, "127.0.0.1", 0, 0, reader, webRoot.toString(),
                orderManager, tracker, adapter);
        server.start();
        OptionsDashboardServerTest.waitForWebSocketPort(server);
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

        JsonNode flat = MAPPER.readTree(get("/api/positions").body());
        assertFalse(flat.get("halted").asBoolean());
        assertEquals("SPY", flat.get("positions").get(0).get("symbol").asText());
        assertEquals(7, flat.get("positions").get(0).get("quantity").asInt());
        assertEquals(1, flat.get("positions").get(0).get("multiplier").asInt());

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
        assertEquals("FREE_SABR", MAPPER.readTree(get("/api/surface3d?model=FREE_SABR").body()).get("model").asText());
        assertEquals("SABR", MAPPER.readTree(get("/api/surface3d?model=SSVIX").body()).get("model").asText(),
                "a longer, unknown value must not match as a prefix");
        assertEquals("SABR", MAPPER.readTree(get("/api/surface3d?x=model%3DSSVI").body()).get("model").asText());
        JsonNode surface = MAPPER.readTree(get("/api/surface3d?model=SSVI").body());
        assertEquals(9, surface.get("x").size());
        assertEquals(9, surface.get("z").size());
        assertEquals(9, surface.get("z").get(0).size());
    }
}
