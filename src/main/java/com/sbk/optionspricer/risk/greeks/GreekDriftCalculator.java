package com.sbk.optionspricer.risk.greeks;

import com.sbk.optionspricer.risk.PortfolioPosition;

import java.util.List;

/**
 * High-performance computation engine for calculating Greek drift over time.
 * Calculates Delta decay (via Charm) and Gamma bleed (via Color) using
 * parallel streams for fast aggregation over large portfolios.
 */
public class GreekDriftCalculator {

    public record GreekDrift(
            double deltaDrift,
            double gammaDrift
    ) {}

    /**
     * Calculates the expected drift in portfolio Greeks over a specified time step.
     *
     * @param positions The list of portfolio positions.
     * @param dt The time step in years (e.g., 1.0 / 365.0 for one day).
     * @return The aggregated Greek drift.
     */
    public static GreekDrift calculateDrift(List<PortfolioPosition> positions, double dt) {
        if (positions == null || positions.isEmpty()) {
            return new GreekDrift(0.0, 0.0);
        }

        // Parallel processing for aggregating drift across large portfolios
        double totalDeltaDrift = positions.parallelStream()
                .mapToDouble(p -> p.getQuantity() * p.getMultiplier() * p.getCharm() * dt)
                .sum();

        double totalGammaDrift = positions.parallelStream()
                .mapToDouble(p -> p.getQuantity() * p.getMultiplier() * p.getColor() * dt)
                .sum();

        return new GreekDrift(totalDeltaDrift, totalGammaDrift);
    }
}
