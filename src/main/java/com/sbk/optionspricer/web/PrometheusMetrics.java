package com.sbk.optionspricer.web;

import com.sbk.optionspricer.execution.Valuation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's numbers in the Prometheus text exposition format, built from what {@code /api/health} and the valuation already report: process state,
 * market-data age, trading halt, per-component latency, execution quality and the book's Greeks and P&amp;L. A value that is unknown is left out rather
 * than written as NaN, so a graph shows a gap instead of a false zero.
 */
public final class PrometheusMetrics {

    private PrometheusMetrics() {}

    public record Sample(String name, String help, String type, Map<String, String> labels, double value) {}

    public static String render(List<Sample> samples) {
        Map<String, List<Sample>> byName = new LinkedHashMap<>();
        for (Sample s : samples) {
            if (Double.isFinite(s.value())) {
                byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
            }
        }
        StringBuilder out = new StringBuilder();
        byName.forEach((name, group) -> {
            Sample first = group.get(0);
            out.append("# HELP ").append(name).append(' ').append(first.help()).append('\n');
            out.append("# TYPE ").append(name).append(' ').append(first.type()).append('\n');
            for (Sample s : group) {
                out.append(name);
                if (!s.labels().isEmpty()) {
                    out.append('{');
                    boolean firstLabel = true;
                    for (Map.Entry<String, String> label : s.labels().entrySet()) {
                        if (!firstLabel) {
                            out.append(',');
                        }
                        firstLabel = false;
                        out.append(label.getKey()).append("=\"").append(escape(label.getValue())).append('"');
                    }
                    out.append('}');
                }
                out.append(' ').append(number(s.value())).append('\n');
            }
        });
        return out.toString();
    }

    private static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** The samples for one scrape. {@code valuation} may be null (no book valued yet). */
    @SuppressWarnings("unchecked")
    public static List<Sample> fromEngine(Map<String, Object> health, Valuation valuation) {
        List<Sample> out = new ArrayList<>();
        out.add(new Sample("engine_up", "1 while the engine process answers", "gauge", Map.of(), 1));
        out.add(new Sample("engine_uptime_seconds", "Seconds since the dashboard server started", "gauge", Map.of(), num(health.get("uptimeSeconds"))));
        double quoteAgeMs = num(health.get("quoteAgeMs"));
        out.add(new Sample("engine_market_data_age_seconds", "Age of the latest spot quote", "gauge", Map.of(), quoteAgeMs / 1000.0));

        Object components = health.get("components");
        if (components instanceof Map<?, ?> c && c.get("trading") instanceof Map<?, ?> trading && trading.get("halted") instanceof Boolean halted) {
            out.add(new Sample("engine_trading_halted", "1 when the trading halt is on", "gauge", Map.of(), halted ? 1 : 0));
        }

        if (health.get("latencyMs") instanceof Map<?, ?> latency) {
            for (Map.Entry<?, ?> e : latency.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> row)) {
                    continue;
                }
                String component = String.valueOf(e.getKey());
                out.add(new Sample("engine_component_calls_total", "Calls timed per component", "counter", labels("component", component), num(row.get("count"))));
                out.add(latencySample(component, "0.5", num(row.get("p50Ms"))));
                out.add(latencySample(component, "0.95", num(row.get("p95Ms"))));
                out.add(latencySample(component, "max", num(row.get("maxMs"))));
            }
        }

        if (health.get("executionQuality") instanceof Map<?, ?> quality) {
            for (Map.Entry<?, ?> e : quality.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> row)) {
                    continue;
                }
                String kind = String.valueOf(e.getKey());
                out.add(new Sample("engine_orders_attempted_total", "Orders that reached the venue", "counter", labels("kind", kind), num(row.get("attempts"))));
                out.add(new Sample("engine_orders_filled_total", "Orders that filled at least in part", "counter", labels("kind", kind), num(row.get("filled"))));
                Map<String, String> mid = labels("kind", kind);
                mid.put("reference", "mid");
                out.add(new Sample("engine_slippage_mean_bps", "Mean cost of fills against the decision-time reference, in basis points", "gauge", mid,
                        num(row.get("meanSlippageVsMidBps"))));
                Map<String, String> touch = labels("kind", kind);
                touch.put("reference", "touch");
                out.add(new Sample("engine_slippage_mean_bps", "Mean cost of fills against the decision-time reference, in basis points", "gauge", touch,
                        num(row.get("meanSlippageVsTouchBps"))));
                out.add(new Sample("engine_slippage_dollars_total", "Total cost of fills against the decision-time mid", "gauge", labels("kind", kind),
                        num(row.get("totalSlippageDollars"))));
            }
        }

        if (valuation != null) {
            out.add(new Sample("engine_net_delta", "Net delta of the book", "gauge", Map.of(), valuation.netDelta()));
            out.add(new Sample("engine_net_gamma", "Net gamma of the book", "gauge", Map.of(), valuation.netGamma()));
            out.add(new Sample("engine_net_vega", "Net vega of the book", "gauge", Map.of(), valuation.netVega()));
            out.add(new Sample("engine_net_theta", "Net theta of the book", "gauge", Map.of(), valuation.netTheta()));
            out.add(new Sample("engine_unrealized_pnl_dollars", "Unrealized P&L of the book", "gauge", Map.of(), valuation.unrealizedPnl()));
            out.add(new Sample("engine_realized_pnl_dollars", "Realized P&L of the book", "gauge", Map.of(), valuation.realizedPnl()));
        }
        return out;
    }

    private static Sample latencySample(String component, String quantile, double value) {
        Map<String, String> labels = labels("component", component);
        labels.put("quantile", quantile);
        return new Sample("engine_component_latency_ms", "Component latency in milliseconds (recent median and 95th percentile, and the maximum ever)", "gauge", labels, value);
    }

    private static Map<String, String> labels(String key, String value) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(key, value);
        return labels;
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }
}
