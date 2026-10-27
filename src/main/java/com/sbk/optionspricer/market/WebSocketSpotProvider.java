package com.sbk.optionspricer.market;

import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * WebSocket-based spot price provider with latency tracking.
 * 
 * Provides real-time spot prices via WebSocket connections and measures
 * end-to-end latency from data source to decision.
 * 
 * TODO: Implement actual WebSocket connection to market data provider.
 * For now, delegates to existing providers and adds latency tracking.
 */
public final class WebSocketSpotProvider implements Supplier<MarketSnapshot> {

    private final Supplier<MarketSnapshot> delegate;
    private final AtomicLong lastUpdateTimestamp = new AtomicLong(0);
    private final AtomicLong dataSourceTimestamp = new AtomicLong(0);
    private final double stalenessThresholdSeconds;

    public WebSocketSpotProvider(Supplier<MarketSnapshot> delegate) {
        this.delegate = delegate;
        this.stalenessThresholdSeconds = Double.parseDouble(
            EnvironmentConfigLoader.getOrDefault("WEBSOCKET_STALENESS_SECONDS", "30")
        );
    }

    @Override
    public MarketSnapshot get() {
        Instant now = Instant.now();
        long nowMillis = now.toEpochMilli();
        
        MarketSnapshot snapshot = delegate.get();
        
        // Update timestamps
        lastUpdateTimestamp.set(nowMillis);
        if (snapshot.sourceTimestamp() != null && !snapshot.sourceTimestamp().equals(Instant.EPOCH)) {
            dataSourceTimestamp.set(snapshot.sourceTimestamp().toEpochMilli());
        }
        
        // Calculate latency
        double latencyMs = calculateLatencyMs(snapshot);
        
        // Update snapshot with latency information
        if (latencyMs > 0) {
            return snapshot.withLatency(latencyMs);
        }
        
        return snapshot;
    }

    /**
     * Calculates end-to-end latency in milliseconds.
     * Latency = time now - data source timestamp.
     */
    private double calculateLatencyMs(MarketSnapshot snapshot) {
        if (snapshot.sourceTimestamp() == null || snapshot.sourceTimestamp().equals(Instant.EPOCH)) {
            return Double.NaN;
        }
        
        long sourceTime = snapshot.sourceTimestamp().toEpochMilli();
        long now = Instant.now().toEpochMilli();
        
        return (now - sourceTime);
    }

    /**
     * Returns the current latency in milliseconds.
     */
    public double getCurrentLatencyMs() {
        long sourceTime = dataSourceTimestamp.get();
        long now = Instant.now().toEpochMilli();
        
        if (sourceTime == 0) {
            return Double.NaN;
        }
        
        return (now - sourceTime);
    }

    /**
     * Returns true if the data is stale (older than staleness threshold).
     */
    public boolean isStale() {
        double latencyMs = getCurrentLatencyMs();
        return Double.isFinite(latencyMs) && (latencyMs / 1000.0) > stalenessThresholdSeconds;
    }

    /**
     * Returns the staleness threshold in seconds.
     */
    public double getStalenessThresholdSeconds() {
        return stalenessThresholdSeconds;
    }
}
