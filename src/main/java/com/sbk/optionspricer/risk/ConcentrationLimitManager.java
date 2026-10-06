package com.sbk.optionspricer.risk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the aggregate notional exposure per underlying to ensure concentration
 * risk remains within configured limits.
 */
public class ConcentrationLimitManager {
    private final Map<String, Double> limitsByUnderlying;
    private final Map<String, Double> exposuresByUnderlying = new ConcurrentHashMap<>();

    public ConcentrationLimitManager(Map<String, Double> limitsByUnderlying) {
        if (limitsByUnderlying == null || limitsByUnderlying.isEmpty()) {
            throw new IllegalArgumentException("limitsByUnderlying must not be null or empty");
        }

        Map<String, Double> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : limitsByUnderlying.entrySet()) {
            String underlying = entry.getKey();
            Double limit = entry.getValue();
            if (underlying == null || underlying.isBlank()) {
                throw new IllegalArgumentException("underlying must not be blank");
            }
            if (limit == null || !Double.isFinite(limit) || limit <= 0.0) {
                throw new IllegalArgumentException("limit for " + underlying + " must be finite and positive");
            }
            validated.put(underlying, limit);
        }
        this.limitsByUnderlying = Collections.unmodifiableMap(validated);
    }

    public void addExposure(String underlying, double exposure) {
        if (underlying == null || underlying.isBlank()) {
            throw new IllegalArgumentException("underlying must not be blank");
        }
        if (!Double.isFinite(exposure)) {
            throw new IllegalArgumentException("exposure must be finite");
        }
        // Signed: buys add notional, sells subtract it, so exposure can shrink and go net short.
        exposuresByUnderlying.merge(underlying, exposure, Double::sum);
    }

    public boolean hasLimit(String underlying) {
        return underlying != null && limitsByUnderlying.containsKey(underlying);
    }

    public boolean isWithinLimit(String underlying, double exposure) {
        double limit = resolveLimit(underlying);
        return Math.abs(exposure) <= limit;
    }

    public double getLimit(String underlying) {
        return resolveLimit(underlying);
    }

    public double getExposure(String underlying) {
        return exposuresByUnderlying.getOrDefault(underlying, 0.0);
    }

    public Map<String, Double> snapshot() {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        for (String underlying : limitsByUnderlying.keySet()) {
            snapshot.put(underlying, exposuresByUnderlying.getOrDefault(underlying, 0.0));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private double resolveLimit(String underlying) {
        Double limit = limitsByUnderlying.get(underlying);
        if (limit == null) {
            throw new IllegalArgumentException("No concentration limit configured for underlying: " + underlying);
        }
        return limit;
    }
}
