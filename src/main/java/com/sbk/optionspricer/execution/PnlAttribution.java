package com.sbk.optionspricer.execution;

import java.time.Instant;

/**
 * Quantitative P&L attribution decomposing mark-to-market changes into Greeks Taylor expansion:
 * ΔΠ = ΔΠ_delta + ΔΠ_gamma + ΔΠ_theta + ΔΠ_vega + Residual
 */
public record PnlAttribution(
        Instant timestamp,
        double spotChange,
        double timeChangeYears,
        double totalPnlChange,
        double deltaPnl,
        double gammaPnl,
        double thetaPnl,
        double gammaThetaPnl,
        double vegaPnl,
        double residualPnl,
        // Cumulative totals
        double cumulativeTotalPnl,
        double cumulativeDeltaPnl,
        double cumulativeGammaPnl,
        double cumulativeThetaPnl,
        double cumulativeGammaThetaPnl,
        double cumulativeVegaPnl,
        double cumulativeResidualPnl
) {
    public static final PnlAttribution ZERO = new PnlAttribution(
            Instant.EPOCH, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    );
}
