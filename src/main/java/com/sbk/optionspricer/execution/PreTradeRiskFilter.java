package com.sbk.optionspricer.execution;

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
    private long lastSecondWindow;
    private int ordersInCurrentSecond;

    public PreTradeRiskFilter(int maxOrderQuantity, double maxOrderNotional, int maxOrdersPerSecond) {
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxOrderNotional = maxOrderNotional;
        this.maxOrdersPerSecond = maxOrdersPerSecond;
        this.lastSecondWindow = System.nanoTime() / 1_000_000_000L;
        this.ordersInCurrentSecond = 0;
    }

    /**
     * Checks if the order is safe to send to the exchange.
     * @return true if safe, false if blocked
     */
    public synchronized boolean checkRisk(Order order) {
        // 0. Base Domain Constraints
        if (order.quantity() <= 0) {
            System.err.printf("[RISK BLOCK] Invalid quantity: %d%n", order.quantity());
            return false;
        }
        if (order.price() <= 0 || Double.isNaN(order.price()) || Double.isInfinite(order.price())) {
            System.err.printf("[RISK BLOCK] Invalid price: %f%n", order.price());
            return false;
        }

        // 1. Fat Finger Size Check
        if (order.quantity() > maxOrderQuantity) {
            System.err.printf("[RISK BLOCK] Order size %d exceeds max %d%n", order.quantity(), maxOrderQuantity);
            return false;
        }

        // 2. Fat Finger Notional Value Check (Quantity * Price)
        double notional = order.quantity() * order.price();
        if (notional > maxOrderNotional) {
            System.err.printf("[RISK BLOCK] Notional value %.2f exceeds max %.2f%n", notional, maxOrderNotional);
            return false;
        }

        // 3. Message Rate Throttle (QTR Check)
        long currentSecond = System.nanoTime() / 1_000_000_000L;
        if (currentSecond == lastSecondWindow) {
            ordersInCurrentSecond++;
            if (ordersInCurrentSecond > maxOrdersPerSecond) {
                System.err.printf("[RISK BLOCK] Message rate exceeded %d msgs/sec%n", maxOrdersPerSecond);
                return false;
            }
        } else {
            lastSecondWindow = currentSecond;
            ordersInCurrentSecond = 1;
        }

        return true;
    }
}
