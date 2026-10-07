package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.ConcentrationLimitManager;
import com.sbk.optionspricer.risk.LiquidityRiskMonitor;

import java.util.Collections;
import java.util.Map;

/**
 * Pre-trade risk filter: constant-time checks with no allocation on the accept path (latency is not benchmarked,
 * and the check is synchronized). This is the final line of defense before an order is transmitted.
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

    /** Records an executed fill (signed notional: buys positive, sells negative) toward concentration exposure. */
    public void recordFill(String underlying, double signedNotional) {
        if (concentrationLimitManager != null && underlying != null && concentrationLimitManager.hasLimit(underlying)) {
            concentrationLimitManager.addExposure(underlying, signedNotional);
        }
    }

    /**
     * Checks if the order is safe to send to the exchange.
     * @return true if safe, false if blocked
     */
    public synchronized boolean checkRisk(Order order) {
        return checkRisk(order, null, Double.NaN, Double.NaN, -1L);
    }

    public synchronized boolean checkRisk(Order order, String underlying, double bid, double ask, long volume) {
        return checkRisk(order, underlying, bid, ask, volume, 1);
    }

    /** @param multiplier units of the underlying per contract: the order notional is quantity x price x multiplier */
    public synchronized boolean checkRisk(Order order, String underlying, double bid, double ask, long volume, int multiplier) {
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier must be at least 1");
        }
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
        double notional = (double) order.quantity() * order.price() * multiplier;
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
                if (!concentrationLimitManager.hasLimit(configuredUnderlying)) {
                    System.err.printf(java.util.Locale.ROOT, "[RISK BLOCK] No concentration limit configured for %s%n", configuredUnderlying);
                    return false;
                }
                double signedNotional = order.isBuy() ? notional : -notional;
                double currentExposure = concentrationLimitManager.getExposure(configuredUnderlying);
                double projectedExposure = Math.abs(currentExposure + signedNotional);
                // Orders that reduce an existing exposure are never blocked by the concentration limit.
                if (projectedExposure > concentrationLimitManager.getLimit(configuredUnderlying)
                        && projectedExposure > Math.abs(currentExposure)) {
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
