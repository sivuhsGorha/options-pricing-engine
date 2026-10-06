package com.sbk.optionspricer.core;

import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.risk.GreekAggregator;
import com.sbk.optionspricer.risk.MarginApproximation;
import com.sbk.optionspricer.risk.PortfolioPosition;
import com.sbk.optionspricer.risk.FillRecorder;
import com.sbk.optionspricer.web.MmapStateReader;
import com.sbk.optionspricer.web.OptionsDashboardServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The risk the engine publishes must come from real positions, not seeded constants or noise. */
class EngineRiskStateTest {

    private record Published(double delta, double gamma, double vega, double margin) {}

    private static UnifiedQuantEngine engine(List<Published> sink) {
        System.setProperty("MMAP_STATE_FILE", "target/engine_risk_state_test.dat");
        MmapStatePublisher capturing = new MmapStatePublisher() {
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
                sink.add(new Published(netDelta, netGamma, netVega, scenarioMargin));
            }
        };
        return new UnifiedQuantEngine(capturing, code -> fail("engine must not exit: " + code));
    }

    @Test
    void flatBookPublishesZeroRiskAndRaisesNoAlerts() {
        List<Published> published = new ArrayList<>();
        UnifiedQuantEngine engine = engine(published);
        AtomicInteger alerts = new AtomicInteger();
        engine.getGreekAlertManager().addAlertListener(a -> alerts.incrementAndGet());

        for (int i = 0; i < 5; i++) {
            engine.processTick(100.0);
        }

        assertEquals(5, published.size());
        for (Published p : published) {
            assertEquals(new Published(0.0, 0.0, 0.0, 0.0), p, "an empty book has no risk and no margin");
        }
        assertEquals(0, alerts.get(), "no positions means nothing can breach a limit");
    }

    @Test
    void publishedRiskComesFromTheExposureSourceAndIsDeterministic() {
        List<Published> published = new ArrayList<>();
        UnifiedQuantEngine engine = engine(published);
        engine.setExposureSource(() -> new PositionTracker.PortfolioExposure(1_000.0, 20.0, 300.0, 0.0));

        engine.processTick(100.0);
        engine.processTick(100.0);

        double expectedMargin = MarginApproximation.calculateInitialMargin(1_000.0, 20.0, 300.0, 100.0);
        assertTrue(expectedMargin > 0.0);
        assertEquals(new Published(1_000.0, 20.0, 300.0, expectedMargin), published.get(0));
        assertEquals(published.get(0), published.get(1), "identical inputs must publish identical risk (no random noise)");
    }

    @Test
    void publishedRiskFollowsTheRealPositionTracker() {
        List<Published> published = new ArrayList<>();
        UnifiedQuantEngine engine = engine(published);
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        engine.setExposureSource(tracker::snapshotExposure);

        engine.processTick(100.0);
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", -10, 100, 100.0));
        engine.processTick(100.0);

        assertEquals(0.0, published.get(0).delta());
        assertEquals(-1_000.0, published.get(1).delta(), 1e-9, "short 10 x 100 shares must publish -1000 delta");
    }

    @Test
    void marginIsUnavailableNotInventedWhenThereIsNoMarketPrice() {
        List<Published> published = new ArrayList<>();
        UnifiedQuantEngine engine = engine(published);
        engine.setExposureSource(() -> new PositionTracker.PortfolioExposure(1_000.0, 20.0, 300.0, 0.0));

        engine.processTick(Double.NaN);

        assertEquals(1_000.0, published.get(0).delta(), "position Greeks do not need a market price");
        assertTrue(Double.isNaN(published.get(0).margin()), "scenario margin needs a spot; without one it is unknown, not 0 or a guess");
    }

    @Test
    void riskEndpointReportsUnknownMarginAsNullNotZero() throws Exception {
        MmapStateReader.RiskState state = new MmapStateReader.RiskState(-250.0, 3.0, 40.0, Double.NaN);

        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(OptionsDashboardServer.riskJson(state, new PositionTracker.PortfolioExposure(-250.0, 3.0, 40.0, 0.0)));

        assertTrue(node.get("scenarioMargin").isNull(), node.toString());
        assertEquals(-250.0, node.get("netDelta").asDouble(), 1e-9);
    }

    @Test
    void marginOverloadMatchesTheAggregatorVersion() {
        PortfolioPosition position = new PortfolioPosition("SPY", 10, 100, FillRecorder.NONE);
        position.updateGreeks(0.5, 0.02, 40.0);
        GreekAggregator aggregator = new GreekAggregator(1e9, 1e9);
        aggregator.addPosition(position);

        assertEquals(MarginApproximation.calculateInitialMargin(aggregator, 450.0),
                MarginApproximation.calculateInitialMargin(500.0, 20.0, 40_000.0, 450.0), 1e-9);
    }

    @Test
    void riskEndpointDoesNotInventNumbersItDoesNotCompute() {
        MmapStateReader.RiskState state = new MmapStateReader.RiskState(-250.0, 3.0, 40.0, 12_345.0);
        PositionTracker.PortfolioExposure tracked = new PositionTracker.PortfolioExposure(-250.0, 3.0, 40.0, 25_000.0);

        String json = OptionsDashboardServer.riskJson(state, tracked);

        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("riskJson must be valid JSON: " + json, e);
        }
        assertEquals(250, node.get("recommendedHedge").asInt(), json);
        assertEquals(12_345.0, node.get("scenarioMargin").asDouble(), 1e-9, json);
        assertTrue(node.get("optimizedMargin").isNull(), "no optimizer exists, so no optimized margin: " + json);
        assertTrue(node.get("marginReductionPct").isNull(), json);
        assertTrue(node.get("l3FillProb").isNull(), "no L3 feed exists: " + json);
        assertTrue(node.get("sorAllocations").isNull(), "no router allocation exists: " + json);
    }
}
