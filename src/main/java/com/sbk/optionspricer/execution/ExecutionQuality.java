package com.sbk.optionspricer.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the orders that reached the venue actually cost against the quote they were decided on, and how often they fill at all. Slippage is
 * signed as a cost: positive when a buy filled above the reference or a sell filled below it, negative for a price improvement. It is measured
 * against the mid (what a patient order would hope for) and against the touch (what a marketable limit was prepared to pay). An order that the
 * engine's own gates refused never reached the venue and is not recorded: it says nothing about execution. Kept in memory, per kind of order.
 */
public final class ExecutionQuality {

    private static final ExecutionQuality SHARED = new ExecutionQuality();

    private final Map<String, Series> series = new ConcurrentHashMap<>();

    public static ExecutionQuality shared() {
        return SHARED;
    }

    /**
     * @param kind       "single" or "multileg"
     * @param requested  contracts or shares asked for
     * @param filled     contracts or shares filled; zero when nothing did
     * @param costIsFillAboveReference true for a buy (or a net debit), false for a sell (or a net credit)
     * @param mid        the decision-time mid, NaN or non-positive when there was none
     * @param touch      the decision-time touch on the side traded (ask for a buy, bid for a sell)
     * @param fillPrice  the price it filled at, NaN when nothing filled
     * @param multiplier units per contract, for the dollar figure
     */
    public void record(String kind, int requested, int filled, boolean costIsFillAboveReference, double mid, double touch,
                       double fillPrice, int multiplier) {
        if (kind == null || requested <= 0) {
            return;
        }
        series.computeIfAbsent(kind, k -> new Series()).add(requested, Math.max(0, Math.min(filled, requested)), costIsFillAboveReference,
                mid, touch, fillPrice, Math.max(1, multiplier));
    }

    public Map<String, Stats> snapshot() {
        Map<String, Stats> out = new LinkedHashMap<>();
        series.keySet().stream().sorted().forEach(k -> out.put(k, series.get(k).stats()));
        return out;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        snapshot().forEach((kind, s) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attempts", s.attempts());
            row.put("filled", s.filled());
            row.put("fillRate", round(s.fillRate()));
            row.put("quantityFillRatio", round(s.quantityFillRatio()));
            row.put("meanSlippageVsMidBps", nullIfNaN(s.meanSlippageVsMidBps()));
            row.put("worstSlippageVsMidBps", nullIfNaN(s.worstSlippageVsMidBps()));
            row.put("meanSlippageVsTouchBps", nullIfNaN(s.meanSlippageVsTouchBps()));
            row.put("totalSlippageDollars", round(s.totalSlippageDollars()));
            out.put(kind, row);
        });
        return out;
    }

    private static Object nullIfNaN(double v) {
        return Double.isFinite(v) ? (Object) round(v) : null;
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    public record Stats(long attempts, long filled, double fillRate, double quantityFillRatio, double meanSlippageVsMidBps,
                        double worstSlippageVsMidBps, double meanSlippageVsTouchBps, double totalSlippageDollars) {}

    private static final class Series {
        private long attempts;
        private long filled;
        private long requestedQty;
        private long filledQty;
        private long midSamples;
        private double midBpsSum;
        private double worstMidBps = Double.NaN;
        private long touchSamples;
        private double touchBpsSum;
        private double dollars;

        synchronized void add(int requested, int filledQuantity, boolean debit, double mid, double touch, double fillPrice, int multiplier) {
            attempts++;
            requestedQty += requested;
            if (filledQuantity <= 0 || !Double.isFinite(fillPrice)) {
                return;
            }
            filled++;
            filledQty += filledQuantity;
            double sign = debit ? 1.0 : -1.0;
            if (mid > 0.0 && Double.isFinite(mid)) {
                double bps = sign * (fillPrice - mid) / mid * 10_000.0;
                midSamples++;
                midBpsSum += bps;
                worstMidBps = Double.isNaN(worstMidBps) ? bps : Math.max(worstMidBps, bps);
                dollars += sign * (fillPrice - mid) * filledQuantity * multiplier;
            }
            if (touch > 0.0 && Double.isFinite(touch)) {
                touchSamples++;
                touchBpsSum += sign * (fillPrice - touch) / touch * 10_000.0;
            }
        }

        synchronized Stats stats() {
            return new Stats(attempts, filled, attempts == 0 ? Double.NaN : (double) filled / attempts,
                    requestedQty == 0 ? Double.NaN : (double) filledQty / requestedQty,
                    midSamples == 0 ? Double.NaN : midBpsSum / midSamples, worstMidBps,
                    touchSamples == 0 ? Double.NaN : touchBpsSum / touchSamples, dollars);
        }
    }
}
