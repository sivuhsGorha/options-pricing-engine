package com.sbk.optionspricer.web;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.execution.Valuation;
import com.sbk.optionspricer.execution.ValuationSource;
import com.sbk.optionspricer.instruments.OccSymbol;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** /api/stress: the book revalued under the named scenarios, or an honest "not ready". */
class StressBodyTest {

    private static ValuationSource source(Valuation valuation, String status) {
        return new ValuationSource() {
            @Override public Optional<Valuation> latestValuation() { return Optional.ofNullable(valuation); }
            @Override public String valuationStatus() { return status; }
        };
    }

    @Test
    void withoutAValuationItSaysWhyAndStressesNothing() {
        assertEquals(false, OptionsDashboardServer.stressBody(null).get("ready"));
        Map<String, Object> body = OptionsDashboardServer.stressBody(source(null, "waiting for the first valuation"));

        assertEquals(false, body.get("ready"));
        assertEquals("waiting for the first valuation", body.get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aValuedBookGetsEveryScenarioTheWorstOneTheUnvaluedPositionsAndTheAssumptions() {
        LocalDate expiry = LocalDate.of(2026, 11, 20);
        Valuation.PositionValuation call = new Valuation.PositionValuation(OccSymbol.format("SPY", expiry, OptionType.CALL, 780), "OPTION", -2, 100, 9.0, "MID",
                9.0, 0.0, 0.18, "MARKET_IV", 0.5, 0.01, 60, -20, 1, false, "");
        Valuation.PositionValuation noVol = new Valuation.PositionValuation(OccSymbol.format("SPY", expiry, OptionType.PUT, 700), "OPTION", 1, 100, 1.0, "MID",
                1.0, 0.0, Double.NaN, "NONE", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, "");
        Valuation valuation = new Valuation(Instant.parse("2026-10-21T15:00:00Z"), 777.0, "TEST", List.of(call, noVol), 0, 0, 0, 0, 0, 0, 0, List.of());

        Map<String, Object> body = OptionsDashboardServer.stressBody(source(valuation, ""));

        assertEquals(true, body.get("ready"));
        assertEquals(13, ((List<Object>) body.get("scenarios")).size());
        Map<String, Object> worst = (Map<String, Object>) body.get("worst");
        assertTrue((Double) worst.get("pnl") < 0.0, "a short call loses somewhere: " + worst);
        assertEquals(List.of(OccSymbol.format("SPY", expiry, OptionType.PUT, 700)), body.get("unvalued"));
        assertTrue(String.valueOf(body.get("assumptions")).contains("Black-Scholes"), "the method is stated with the numbers");
    }
}
