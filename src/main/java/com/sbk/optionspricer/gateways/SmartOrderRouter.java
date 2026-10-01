package com.sbk.optionspricer.gateways;

import java.util.ArrayList;
import java.util.List;

/**
 * Smart Order Router (SOR) for European Derivative Markets.
 * Calculates latency-equalized sub-order splits across venues (Eurex vs Euronext vs LSEG)
 * to hit matching engines simultaneously and avoid adverse selection.
 */
public final class SmartOrderRouter {

    public enum Venue {
        EUREX_T7(1.2),      // 1.2 us network RTT
        EURONEXT_OPTIQ(2.5), // 2.5 us network RTT
        LSEG_SOLA(3.8);     // 3.8 us network RTT

        public final double latencyMicros;

        Venue(double latencyMicros) {
            this.latencyMicros = latencyMicros;
        }
    }

    public static class SubOrder {
        public final Venue venue;
        public final int allocatedQty;
        public final double delayOffsetMicros;

        public SubOrder(Venue venue, int allocatedQty, double delayOffsetMicros) {
            this.venue = venue;
            this.allocatedQty = allocatedQty;
            this.delayOffsetMicros = delayOffsetMicros;
        }
    }

    private SmartOrderRouter() {}

    /**
     * Routes a total order volume across available venues with latency equalization offsets.
     */
    public static List<SubOrder> routeOrder(int totalQuantity, double[] venueLiquidityWeights) {
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("Total quantity must be positive");
        }
        
        Venue[] venues = Venue.values();
        if (venueLiquidityWeights == null || venueLiquidityWeights.length != venues.length) {
            throw new IllegalArgumentException("Must provide exactly one weight per venue");
        }

        double maxLatency = 0.0;
        for (Venue v : venues) {
            maxLatency = Math.max(maxLatency, v.latencyMicros);
        }

        double totalWeight = 0.0;
        for (double w : venueLiquidityWeights) {
            if (!Double.isFinite(w) || w < 0) {
                throw new IllegalArgumentException("Weights must be finite non-negative numbers");
            }
            totalWeight += w;
        }
        
        if (totalWeight <= 0.0) {
            throw new IllegalArgumentException("Total weight must be positive");
        }

        List<SubOrder> subOrders = new ArrayList<>();
        int remainingQty = totalQuantity;
        for (int i = 0; i < venues.length; i++) {
            Venue venue = venues[i];
            int qty = (i == venues.length - 1) ? remainingQty : (int) Math.round(totalQuantity * (venueLiquidityWeights[i] / totalWeight));
            
            // Prevent overallocating before the last venue
            if (i < venues.length - 1) {
                qty = Math.min(qty, remainingQty);
            }
            
            remainingQty -= qty;

            double delayOffset = maxLatency - venue.latencyMicros;
            if (qty > 0) {
                subOrders.add(new SubOrder(venue, qty, delayOffset));
            }
        }

        return subOrders;
    }
}
