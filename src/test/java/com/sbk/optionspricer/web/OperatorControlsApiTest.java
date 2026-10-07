package com.sbk.optionspricer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbk.optionspricer.core.OperatorConsole;
import com.sbk.optionspricer.execution.ExecutionResult;
import com.sbk.optionspricer.execution.Order;
import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.execution.StrategySwitch;
import com.sbk.optionspricer.execution.TradingHalt;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** /api/control: authenticated reads, POST-only state changes guarded against cross-site requests. */
class OperatorControlsApiTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OptionsDashboardServer server;
    private Path webRoot;
    private OrderManager manager;
    private boolean strategyEnabled = true;

    @BeforeEach
    void start() throws Exception {
        TradingHalt halt = new TradingHalt();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.strict(), halt, null);
        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", manager, new PortfolioRiskAdmission(1e12, 1e12, 1e12, 1e12, 1e12), tracker, 10, 0.001);
        StrategySwitch sw = new StrategySwitch() {
            @Override
            public boolean isStrategyEnabled() {
                return strategyEnabled;
            }

            @Override
            public void setStrategyEnabled(boolean enabled) {
                strategyEnabled = enabled;
            }
        };
        MmapStateReader reader = new MmapStateReader(true) {
            @Override
            public RiskState readState() {
                return new RiskState(0, 0, 0, 0);
            }

            @Override
            public void close() {}
        };
        webRoot = Files.createTempDirectory("control-api-web");
        server = new OptionsDashboardServer(OptionsDashboardServerTest.TEST_SECRET, OptionsDashboardServerTest.OPERATOR_PASSWORD,
                OptionsDashboardServerTest.ALLOWED_ORIGIN, "127.0.0.1", 0, 0, reader, webRoot.toString(), manager, tracker, null);
        server.setOperatorControls(new OperatorConsole(halt, sw, loop));
        server.start();
        OptionsDashboardServerTest.waitForWebSocketPort(server);
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.stop();
        if (webRoot != null) Files.deleteIfExists(webRoot);
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

    private HttpResponse<String> call(String method, String path, String body, String cookie, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static MarketSnapshot live() {
        return new MarketSnapshot("SPY", 99.99, 100.01, 100.0, 5000L, Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    @Test
    void theStateIsReadableAndHaltAndResumeChangeWhatTheOrderManagerDoes() throws Exception {
        String cookie = cookie();
        String origin = OptionsDashboardServerTest.ALLOWED_ORIGIN;

        JsonNode initial = MAPPER.readTree(call("GET", "/api/control", null, cookie, origin).body());
        assertFalse(initial.get("halted").asBoolean());
        assertTrue(initial.get("strategyEnabled").asBoolean());
        assertEquals("SPY", initial.get("symbol").asText());
        assertEquals(0.001, initial.get("triggerPct").asDouble(), 1e-12);
        assertEquals(10, initial.get("baseQuantity").asInt());

        HttpResponse<String> halted = call("POST", "/api/control/halt", "{\"reason\":\"manual check\"}", cookie, origin);
        assertEquals(200, halted.statusCode(), halted.body());
        JsonNode haltedBody = MAPPER.readTree(halted.body());
        assertTrue(haltedBody.get("halted").asBoolean());
        assertEquals("operator: manual check", haltedBody.get("haltReason").asText());
        assertFalse(manager.submit(new Order(1, true, 1, 100.0), live()).accepted(), "the halt must bite in the order manager");

        JsonNode resumed = MAPPER.readTree(call("POST", "/api/control/resume", null, cookie, origin).body());
        assertFalse(resumed.get("halted").asBoolean());
        assertTrue(manager.submit(new Order(2, true, 1, 100.0), live()).accepted());

        JsonNode off = MAPPER.readTree(call("POST", "/api/control/strategy", "{\"enabled\":false}", cookie, origin).body());
        assertFalse(off.get("strategyEnabled").asBoolean());
        assertFalse(strategyEnabled);
        assertEquals(400, call("POST", "/api/control/strategy", "{\"enabled\":\"yes\"}", cookie, origin).statusCode());
    }

    @Test
    void stateChangesRequirePostAndAnAllowedOriginAndASession() throws Exception {
        String cookie = cookie();
        String origin = OptionsDashboardServerTest.ALLOWED_ORIGIN;

        assertEquals(405, call("GET", "/api/control/halt", null, cookie, origin).statusCode(), "a GET must not change state");
        assertEquals(405, call("POST", "/api/control", "{}", cookie, origin).statusCode(), "the state endpoint is read-only");
        assertEquals(401, call("POST", "/api/control/halt", "{}", null, null).statusCode(), "no session, no signature: refused");
        assertEquals(401, call("POST", "/api/control/halt", "{}", cookie, "https://evil.example").statusCode(),
                "a cookie-bearing request from a foreign origin is refused");
        assertEquals(404, call("POST", "/api/control/unknown", "{}", cookie, origin).statusCode());

        JsonNode state = MAPPER.readTree(call("GET", "/api/control", null, cookie, origin).body());
        assertFalse(state.get("halted").asBoolean(), "none of the refused calls may have tripped the halt");
    }
}
