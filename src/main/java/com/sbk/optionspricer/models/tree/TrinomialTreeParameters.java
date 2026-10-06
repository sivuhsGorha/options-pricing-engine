package com.sbk.optionspricer.models.tree;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

/**
 * Calculates transition probabilities and jump sizes for a Trinomial Tree
 * matching the first two moments (mean and variance) of the geometric Brownian
 * motion process for the underlying asset.
 *
 * <p>Matching the moments with the fixed spacing dx = vol * sqrt(3 dt) only yields valid probabilities when
 * the per-step drift is small relative to dx. For a large drift, a low volatility or too few steps a
 * probability goes negative; that is rejected here with a message saying to use more steps, because a tree
 * with negative probabilities silently produces meaningless prices.
 *
 * <p>The record holds only primitives, so it is cheap to create; the pricer itself allocates one working array.
 */
public record TrinomialTreeParameters(
        double dt,
        double upFactor,
        double downFactor,
        double pUp,
        double pMid,
        double pDown,
        double discountFactor
) {
    public static TrinomialTreeParameters calculate(OptionParameters p, int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be at least 1");
        }
        double dt = p.timeToExpiry() / steps;
        
        // Ensure numerical stability for very short expiries or low vol
        if (dt <= 1e-10 || p.volatility() <= 1e-10) {
            return new TrinomialTreeParameters(dt, 1.0, 1.0, 0.0, 1.0, 0.0, 1.0);
        }

        // Standard trinomial spacing: dx = vol * sqrt(3 * dt)
        double dx = p.volatility() * Math.sqrt(3.0 * dt);
        double upFactor = Math.exp(dx);
        double downFactor = Math.exp(-dx);

        // Drift (nu) = r - q - 0.5 * vol^2
        double nu = p.riskFreeRate() - p.dividendYield() - 0.5 * p.volatility() * p.volatility();
        
        // Variance and drift components for probability matching
        double varDt = p.volatility() * p.volatility() * dt;
        double driftDt = nu * dt;
        double dxSquared = dx * dx;

        double pUp = 0.5 * ((varDt + driftDt * driftDt) / dxSquared + driftDt / dx);
        double pMid = 1.0 - (varDt + driftDt * driftDt) / dxSquared;
        double pDown = 0.5 * ((varDt + driftDt * driftDt) / dxSquared - driftDt / dx);

        double discountFactor = Math.exp(-p.riskFreeRate() * dt);

        if (!(pUp >= 0.0 && pMid >= 0.0 && pDown >= 0.0)) {
            throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                    "trinomial tree has negative probabilities (up=%.4f mid=%.4f down=%.4f) with %d steps; use more steps "
                            + "or a model that does not require a fixed spacing", pUp, pMid, pDown, steps));
        }

        return new TrinomialTreeParameters(dt, upFactor, downFactor, pUp, pMid, pDown, discountFactor);
    }
}
