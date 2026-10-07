package com.sbk.optionspricer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.risk.MarginApproximation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The hedge analysis on the risk panel is the real scenario margin after flattening delta, or nothing. */
class RiskJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode risk(double delta, double gamma, double vega, double spot) throws Exception {
        double scenarioMargin = MarginApproximation.calculateInitialMargin(delta, gamma, vega, spot);
        MmapStateReader.RiskState state = new MmapStateReader.RiskState(delta, gamma, vega, scenarioMargin);
        PositionTracker.PortfolioExposure exposure = new PositionTracker.PortfolioExposure(delta, gamma, vega, Math.abs(delta) * spot);
        return MAPPER.readTree(OptionsDashboardServer.riskJson(state, exposure, spot));
    }

    @Test
    void forAPureDeltaBookFlatteningDeltaRemovesTheWholeScenarioMargin() throws Exception {
        JsonNode body = risk(-20, 0, 0, 774.5);

        assertEquals(20, body.get("recommendedHedge").asInt(), "buy 20 to flatten a short 20 delta");
        assertTrue(body.get("scenarioMargin").asDouble() > 0);
        assertEquals(0.0, body.get("optimizedMargin").asDouble(), 1e-9, "no gamma or vega: nothing is left to stress");
        assertEquals(100.0, body.get("marginReductionPct").asDouble(), 1e-9);
        assertFalse(body.has("l3FillProb") || body.has("sorAllocations"), "fields that were always null are gone");
    }

    @Test
    void forAShortGammaBookTheHedgedMarginIsTheRemainingStressNotZero() throws Exception {
        // Short gamma and short vega lose in every stress corner, so flattening delta removes only the delta part.
        JsonNode body = risk(-20, -0.5, -50, 774.5);

        double expected = MarginApproximation.calculateInitialMargin(0.0, -0.5, -50, 774.5);
        assertEquals(expected, body.get("optimizedMargin").asDouble(), 0.01);
        double reduction = body.get("marginReductionPct").asDouble();
        assertTrue(reduction > 0.0 && reduction < 100.0, "reduction " + reduction);
    }

    @Test
    void withoutASpotTheHedgeAnalysisReportsNothingRatherThanAGuess() throws Exception {
        MmapStateReader.RiskState state = new MmapStateReader.RiskState(-20, 0, 0, 2323.5);
        PositionTracker.PortfolioExposure exposure = new PositionTracker.PortfolioExposure(-20, 0, 0, 15451.28);

        JsonNode body = MAPPER.readTree(OptionsDashboardServer.riskJson(state, exposure));

        assertEquals(20, body.get("recommendedHedge").asInt());
        assertTrue(body.get("optimizedMargin").isNull());
        assertTrue(body.get("marginReductionPct").isNull());
    }
}
