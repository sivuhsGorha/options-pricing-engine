package com.sbk.optionspricer.models.tree;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

/**
 * Calculates transition probabilities and jump sizes for a Trinomial Tree
 * matching the first two moments (mean and variance) of the geometric Brownian
 * motion process for the underlying asset.
 *
 * All fields are primitives to ensure zero-allocation during hot-path execution.
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

        return new TrinomialTreeParameters(dt, upFactor, downFactor, pUp, pMid, pDown, discountFactor);
    }
}
