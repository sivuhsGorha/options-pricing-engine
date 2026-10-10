package com.sbk.optionspricer.web;

import com.sbk.optionspricer.core.OperatorConsole;
import com.sbk.optionspricer.execution.ExecutionResult;
import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.execution.StrategySwitch;
import com.sbk.optionspricer.execution.TradingHalt;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found on review (2026-10-10): the signature covered the method, path, query, timestamp and nonce but the server
 * never gave the request body to the verifier, so a signed {@code {"enabled":false}} to /api/control/strategy could
 * be changed to {@code {"enabled":true}} in transit and still verify. (The test named for a tampered body tampered
 * the path.) A signature now covers the body too: a client signs a SHA-256 digest of it, appended to the payload when
 * the body is not empty.
 */
class SignedControlBodyTest {
    private static final AtomicInteger NONCE = new AtomicInteger();

    private OptionsDashboardServer server;
    private Path webRoot;
    private volatile boolean strategyEnabled = true;

    @BeforeEach
    void start() throws Exception {
        TradingHalt halt = new TradingHalt();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e9, 1000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"),
                tracker, OrderManager.MarketDataPolicy.strict(), halt, null);
        StrategyExecutionLoop loop = new StrategyExecutionLoop("SPY", manager, new PortfolioRiskAdmission(1e12, 1e12, 1e12, 1e12, 1e12), tracker, 10, 0.001);
        StrategySwitch sw = new StrategySwitch() {
            @Override public boolean isStrategyEnabled() { return strategyEnabled; }
            @Override public void setStrategyEnabled(boolean enabled) { strategyEnabled = enabled; }
        };
        MmapStateReader reader = new MmapStateReader(true) {
            @Override public RiskState readState() { return new RiskState(0, 0, 0, 0); }
            @Override public void close() { }
        };
        webRoot = Files.createTempDirectory("signed-control-web");
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

    /** What a client signs: method, path, query, timestamp, nonce, and for a non-empty body the base64 SHA-256 digest. */
    private static String sign(String method, String path, String ts, String nonce, String bodySigned) throws Exception {
        String payload = method + "\n" + path + "\n" + "" + "\n" + ts + "\n" + nonce;
        if (bodySigned != null && !bodySigned.isEmpty()) {
            payload += "\n" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bodySigned.getBytes(StandardCharsets.UTF_8)));
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(OptionsDashboardServerTest.TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** POSTs {@code bodySent}, signed over {@code bodySigned}: they differ when the body was changed in transit. */
    private HttpResponse<String> post(String path, String bodySigned, String bodySent, String nonce) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
                .header("X-Timestamp", ts).header("X-Nonce", nonce).header("X-Signature", sign("POST", path, ts, nonce, bodySigned))
                .POST(bodySent == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(bodySent, StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String nonce() {
        return "body-test-" + NONCE.incrementAndGet() + "-" + System.nanoTime();
    }

    @Test
    void aSignedBodyIsAcceptedAndActedOn() throws Exception {
        String body = "{\"enabled\":false}";

        HttpResponse<String> response = post("/api/control/strategy", body, body, nonce());

        assertEquals(200, response.statusCode(), response.body());
        assertFalse(strategyEnabled, "the signed instruction was carried out");
    }

    @Test
    void aBodyChangedInTransitFailsTheSignatureAndNothingHappens() throws Exception {
        HttpResponse<String> response = post("/api/control/strategy", "{\"enabled\":false}", "{\"enabled\":true}", nonce());

        assertEquals(401, response.statusCode());
        assertTrue(strategyEnabled, "the switch was not touched");

        strategyEnabled = false;
        HttpResponse<String> flipped = post("/api/control/strategy", "{\"enabled\":true}", "{\"enabled\":false}", nonce());
        assertEquals(401, flipped.statusCode());
        assertFalse(strategyEnabled);
    }

    @Test
    void aBodyAddedToARequestSignedWithoutOneIsRefused() throws Exception {
        HttpResponse<String> response = post("/api/control/halt", null, "{\"reason\":\"injected\"}", nonce());

        assertEquals(401, response.statusCode(), "the signature covered an empty body");
    }

    @Test
    void aSignedRequestWithNoBodyStillWorks() throws Exception {
        HttpResponse<String> response = post("/api/control/resume", null, null, nonce());

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void aSignedBodyCannotBeReplayed() throws Exception {
        String body = "{\"enabled\":false}";
        String nonce = nonce();
        assertEquals(200, post("/api/control/strategy", body, body, nonce).statusCode());

        assertEquals(401, post("/api/control/strategy", body, body, nonce).statusCode(), "the nonce was spent");
    }
}
