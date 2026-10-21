package com.sbk.optionspricer.web;

import com.sbk.optionspricer.execution.Valuation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The engine's numbers in the text format a Prometheus server scrapes. */
class PrometheusMetricsTest {

    @Test
    void aSampleRendersAsHelpTypeAndOneLinePerLabelSet() {
        String text = PrometheusMetrics.render(List.of(
                new PrometheusMetrics.Sample("engine_net_delta", "Net delta of the book", "gauge", Map.of(), 12.5),
                new PrometheusMetrics.Sample("engine_orders_total", "Orders that reached the venue", "counter", Map.of("kind", "single"), 3),
                new PrometheusMetrics.Sample("engine_orders_total", "Orders that reached the venue", "counter", Map.of("kind", "multileg"), 1)));

        assertTrue(text.contains("# HELP engine_net_delta Net delta of the book\n# TYPE engine_net_delta gauge\nengine_net_delta 12.5\n"), text);
        assertEquals(1, text.split("# TYPE engine_orders_total", -1).length - 1, "HELP and TYPE once per metric, however many label sets");
        assertTrue(text.contains("engine_orders_total{kind=\"single\"} 3\n") && text.contains("engine_orders_total{kind=\"multileg\"} 1\n"), text);
    }

    @Test
    void labelValuesAreEscapedAndNonFiniteValuesAreLeftOut() {
        String text = PrometheusMetrics.render(List.of(
                new PrometheusMetrics.Sample("m", "h", "gauge", Map.of("component", "a\"b\\c\nd"), 1),
                new PrometheusMetrics.Sample("n", "h", "gauge", Map.of(), Double.NaN),
                new PrometheusMetrics.Sample("o", "h", "gauge", Map.of(), Double.POSITIVE_INFINITY)));

        assertTrue(text.contains("m{component=\"a\\\"b\\\\c\\nd\"} 1"), text);
        assertFalse(text.contains("\nn ") || text.startsWith("n ") || text.contains("# TYPE n"), "an unknown value is absent, not NaN: " + text);
        assertFalse(text.contains("# TYPE o"), text);
    }

    @Test
    void theEnginesHealthLatencyExecutionAndGreeksBecomeNamedMetrics() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ok");
        health.put("uptimeSeconds", 120L);
        health.put("quoteAgeMs", 4_000L);
        health.put("sourceStatus", "LIVE");
        health.put("components", Map.of("trading", Map.of("halted", true)));
        health.put("latencyMs", Map.of("strategy.step", Map.of("count", 7L, "meanMs", 2.0, "p50Ms", 1.5, "p95Ms", 4.0, "maxMs", 9.0, "lastMs", 1.0)));
        health.put("executionQuality", Map.of("single", Map.of("attempts", 4L, "filled", 3L, "fillRate", 0.75, "quantityFillRatio", 0.9,
                "meanSlippageVsMidBps", 3.5, "meanSlippageVsTouchBps", -1.0, "totalSlippageDollars", 12.0)));
        Valuation valuation = new Valuation(Instant.parse("2026-10-21T12:00:00Z"), 100.0, "TEST", List.of(), -5.0, 20.0, 15.0, 0.5, 80.0, -12.0, 1.0, List.of());

        String text = PrometheusMetrics.render(PrometheusMetrics.fromEngine(health, valuation));

        assertTrue(text.contains("engine_up 1\n"), text);
        assertTrue(text.contains("engine_uptime_seconds 120\n"), text);
        assertTrue(text.contains("engine_trading_halted 1\n"), text);
        assertTrue(text.contains("engine_market_data_age_seconds 4\n"), text);
        assertTrue(text.contains("engine_component_latency_ms{component=\"strategy.step\",quantile=\"0.95\"} 4\n"), text);
        assertTrue(text.contains("engine_component_calls_total{component=\"strategy.step\"} 7\n"), text);
        assertTrue(text.contains("engine_orders_attempted_total{kind=\"single\"} 4\n"), text);
        assertTrue(text.contains("engine_orders_filled_total{kind=\"single\"} 3\n"), text);
        assertTrue(text.contains("engine_slippage_mean_bps{kind=\"single\",reference=\"mid\"} 3.5\n"), text);
        assertTrue(text.contains("engine_net_delta 15\n") && text.contains("engine_net_vega 80\n"), text);
        assertTrue(text.contains("engine_unrealized_pnl_dollars -5\n") && text.contains("engine_realized_pnl_dollars 20\n"), text);
    }

    @Test
    void withoutAValuationOrAnyTradesTheRestStillRenders() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "degraded");
        health.put("uptimeSeconds", 5L);

        String text = PrometheusMetrics.render(PrometheusMetrics.fromEngine(health, null));

        assertTrue(text.contains("engine_up 1\n"), "degraded is up: the process is answering");
        assertFalse(text.contains("engine_net_delta"), text);
        assertFalse(text.contains("engine_orders_attempted_total"), text);
    }
}
