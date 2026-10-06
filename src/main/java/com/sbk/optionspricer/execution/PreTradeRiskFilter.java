package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.LiquidityRiskMonitor;

import java.util.Collections;
import java.util.Map;

/**
 * Sub-microsecond pre-trade risk filter.
 * This is the final line of defense before a packet leaves the server.
 * It prevents "Fat Finger" errors and algorithmic runaways (e.g., Knight Capital).
 */
public class PreTradeRiskFilter {

    private final int maxOrderQuantity;
    private final double maxOrderNotional; // Max monetary value per order

    // Simple Quote-to-Trade (QTR) throttle to prevent spamming the exchange
    private final int maxOrdersPerSecond;
    private final Map<Integer, String> instrumentToUnderlying;
    private final ConcentrationLimitManager concentrationLimitManager;
    private final LiquidityRiskMonitor liquidityRiskMonitor;
    private long lastSecondWindow;
    private int ordersInCurrentSecond;

    public PreTradeRiskFilter(int maxOrderQuantity, double maxOrderNotional, int maxOrdersPerSecond) {
        this(maxOrderQuantity, maxOrderNotional, maxOrdersPerSecond, Map.of(), null, null);
    }

    public PreTradeRiskFilter(int maxOrderQuantity,
                             double maxOrderNotional,
                             int maxOrdersPerSecond,
                             Map<Integer, String> instrumentToUnderlying,
                             ConcentrationLimitManager concentrationLimitManager,
                             LiquidityRiskMonitor liquidityRiskMonitor) {
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxOrderNotional = maxOrderNotional;
        this.maxOrdersPerSecond = maxOrdersPerSecond;
        this.instrumentToUnderlying = instrumentToUnderlying == null ? Map.of() : Collections.unmodifiableMap(instrumentToUnderlying);
        this.concentrationLimitManager = concentrationLimitManager;
        this.liquidityRiskMonitor = liquidityRiskMonitor;
        this.lastSecondWindow = System.nanoTime() / 1_000_000_000L;
        this.ordersInCurrentSecond = 0;
    }

    /**
     * Checks if the order is safe to send to the exchange.
     * @return true if safe, false if blocked
     */
    public synchronized boolean checkRisk(Order order) {
        return checkRisk(order, null, Double.NaN, Double.NaN, -1L);
    }

    public synchronized boolean checkRisk(Order order, String underlying, double bid, double ask, long volume) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }

        // 0. Base Domain Constraints
        if (order.quantity() <= 0) {
            System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] Invalid quantity: %d%n", order.quantity());
            return false;
        }
        if (order.price() <= 0 || Double.isNaN(order.price()) || Double.isInfinite(order.price())) {
            System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] Invalid price: %f%n", order.price());
            return false;
        }

        // 1. Fat Finger Size Check
        if (order.quantity() > maxOrderQuantity) {
            System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] Order size %d exceeds max %d%n", order.quantity(), maxOrderQuantity);
            return false;
        }

        // 2. Fat Finger Notional Value Check (Quantity * Price)
        double notional = order.quantity() * order.price();
        if (notional > maxOrderNotional) {
            System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] Notional value %.2f exceeds max %.2f%n", notional, maxOrderNotional);
            return false;
        }

        if (concentrationLimitManager != null) {
            String configuredUnderlying = underlying;
            if (configuredUnderlying == null || configuredUnderlying.isBlank()) {
                configuredUnderlying = instrumentToUnderlying.get(order.instrumentId());
            }
            if (configuredUnderlying != null && !configuredUnderlying.isBlank()) {
                double projectedExposure = concentrationLimitManager.getExposure(configuredUnderlying) + notional;
                if (projectedExposure > concentrationLimitManager.getLimit(configuredUnderlying)) {
                    System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] Concentration limit exceeded for %s: projected=%.2f limit=%.2f%n",
                            configuredUnderlying, projectedExposure, concentrationLimitManager.getLimit(configuredUnderlying));
                    return false;
                }
            }
        }

        if (liquidityRiskMonitor != null && Double.isFinite(bid) && Double.isFinite(ask) && ask > bid && volume >= 0L) {
            if (!liquidityRiskMonitor.isMarketLiquid(bid, ask, volume)) {
                System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] Liquidity check failed: bid=%.2f ask=%.2f volume=%d%n",
                        bid, ask, volume);
                return false;
            }
        }

        // 3. Message Rate Throttle (QTR Check)
        long currentSecond = System.nanoTime() / 1_000_000_000L;
        if (currentSecond == lastSecondWindow) {
            ordersInCurrentSecond++;
            if (ordersInCurrentSecond > maxOrdersPerSecond) {
                System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] Message rate exceeded %d msgs/sec%n", maxOrdersPerSecond);
                return false;
            }
        } else {
            lastSecondWindow = currentSecond;
            ordersInCurrentSecond = 1;
        }

        return true;
    }
}
