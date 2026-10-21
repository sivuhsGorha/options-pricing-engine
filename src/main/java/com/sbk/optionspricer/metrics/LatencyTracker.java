package com.sbk.optionspricer.metrics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * How long each component of the engine takes (a strategy step, a surface fit, an order submit, a reconciliation), so a slow part can be seen
 * instead of guessed at. Each component keeps a count, a running sum and a maximum over every call, and the most recent {@value #DEFAULT_WINDOW}
 * durations for the median and the 95th percentile: the percentiles describe how the component behaves now, the maximum remembers the worst call ever.
 * Recording is cheap and never throws; the engine's behaviour does not depend on it.
 */
public final class LatencyTracker {

    static final int DEFAULT_WINDOW = 512;
    private static final LatencyTracker SHARED = new LatencyTracker();

    private final int window;
    private final Map<String, Series> series = new ConcurrentHashMap<>();

    public LatencyTracker() {
        this(DEFAULT_WINDOW);
    }

    LatencyTracker(int window) {
        this.window = window;
    }

    /** The tracker the engine's components report to. */
    public static LatencyTracker shared() {
        return SHARED;
    }

    public void record(String component, long nanos) {
        if (component == null || nanos < 0L) {
            return;
        }
        series.computeIfAbsent(component, name -> new Series(window)).add(nanos);
    }

    /** Runs the call and records how long it took, also when it throws. */
    public <T> T time(String component, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            record(component, System.nanoTime() - start);
        }
    }

    public void time(String component, Runnable call) {
        time(component, () -> {
            call.run();
            return null;
        });
    }

    public Map<String, Stats> snapshot() {
        Map<String, Stats> out = new LinkedHashMap<>();
        series.keySet().stream().sorted().forEach(name -> out.put(name, series.get(name).stats()));
        return out;
    }

    /** The snapshot as plain maps and numbers, for a JSON response. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        snapshot().forEach((name, s) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", s.count());
            row.put("meanMs", round(s.meanMs()));
            row.put("p50Ms", round(s.p50Ms()));
            row.put("p95Ms", round(s.p95Ms()));
            row.put("maxMs", round(s.maxMs()));
            row.put("lastMs", round(s.lastMs()));
            out.put(name, row);
        });
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public record Stats(long count, double meanMs, double p50Ms, double p95Ms, double maxMs, double lastMs) {}

    private static final class Series {
        private final long[] recent;
        private int next;
        private int filled;
        private long count;
        private double sumNanos;
        private long maxNanos;
        private long lastNanos;

        Series(int window) {
            this.recent = new long[window];
        }

        synchronized void add(long nanos) {
            recent[next] = nanos;
            next = (next + 1) % recent.length;
            filled = Math.min(filled + 1, recent.length);
            count++;
            sumNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
            lastNanos = nanos;
        }

        synchronized Stats stats() {
            long[] sorted = Arrays.copyOf(recent, filled);
            Arrays.sort(sorted);
            return new Stats(count, sumNanos / count / 1e6, percentile(sorted, 0.50), percentile(sorted, 0.95), maxNanos / 1e6, lastNanos / 1e6);
        }

        private static double percentile(long[] sorted, double p) {
            if (sorted.length == 0) {
                return 0.0;
            }
            int index = (int) Math.ceil(p * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1e6;
        }
    }
}
