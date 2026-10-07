package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.volatility.SlvApproximation.SlvParams;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Monte Carlo Value-at-Risk (VaR) Engine.
 * Simulates portfolio PnL over a holding period using the Heston Stochastic Volatility model.
 */
public class MonteCarloVaRCalculator {

    private static final int NUM_PATHS = 10000;
    private static final int STEPS_PER_DAY = 4; // Sub-steps per day for Euler-Maruyama stability

    /**
     * Calculates the 99% VaR for a portfolio using Heston Monte Carlo simulation.
     * Uses Delta-Gamma-Vega approximation for fast PnL evaluation across 10,000 paths.
     *
     * @param spot Initial spot price
     * @param heston Heston model parameters (v0, kappa, theta, xi, rho)
     * @param riskFreeRate Annualized risk-free rate
     * @param divYield Annualized continuous dividend yield
     * @param holdingPeriodDays VaR horizon (e.g., 10 days)
     * @param netDelta Portfolio delta
     * @param netGamma Portfolio gamma
     * @param netVega Portfolio vega
     * @return The 99% Monte Carlo VaR loss
     */
    public static double calculate99PercentVaR(double spot, SlvParams heston,
                                               double riskFreeRate, double divYield,
                                               int holdingPeriodDays,
                                               double netDelta, double netGamma, double netVega) {

        double dt = 1.0 / (com.sbk.optionspricer.TimeConventions.DAYS_PER_YEAR * STEPS_PER_DAY);
        int totalSteps = holdingPeriodDays * STEPS_PER_DAY;
        double sqrtDt = Math.sqrt(dt);
        double rho = heston.rho;
        double sqrtOneMinusRhoSq = Math.sqrt(1.0 - rho * rho);

        double[] simulatedPnL = new double[NUM_PATHS];

        // Parallel simulation
        Arrays.parallelSetAll(simulatedPnL, i -> {
            SplittableRandom random = new SplittableRandom();
            double s = spot;
            double v = heston.v0;

            for (int step = 0; step < totalSteps; step++) {
                // Generate two independent standard normal random variables (Box-Muller approx or direct)
                double z1 = random.nextGaussian();
                double z2 = random.nextGaussian();

                // Correlated Brownian motions
                double dw1 = z1 * sqrtDt;
                double dw2 = (rho * z1 + sqrtOneMinusRhoSq * z2) * sqrtDt;

                // Heston dynamics (Euler-Maruyama with full truncation for variance)
                double vPositive = Math.max(v, 0.0);
                double sqrtV = Math.sqrt(vPositive);

                s = s * Math.exp((riskFreeRate - divYield - 0.5 * vPositive) * dt + sqrtV * dw1);
                v = v + heston.kappa * (heston.theta - vPositive) * dt + heston.xi * sqrtV * dw2;

                // Truncate variance to prevent negative values in simulation
                v = Math.max(v, 0.0);
            }

            // PnL approximation using Taylor expansion (Delta-Gamma-Vega)
            double dS = s - spot;
            double dVol = Math.sqrt(v) - Math.sqrt(heston.v0); // Convert variance back to vol for vega

            // PnL = Delta * dS + 0.5 * Gamma * dS^2 + Vega * dVol
            return (netDelta * dS) + (0.5 * netGamma * dS * dS) + (netVega * dVol * 100.0);
            // Note: Vega is typically quoted for a 1 point (or 1%) move in vol.
            // Depending on convention, we scale dVol by 100 if Vega is per 1% vol change.
        });

        Arrays.sort(simulatedPnL);

        // 99% VaR is the 1st percentile of sorted PnL
        int index99 = Math.max(0, (int) Math.floor((NUM_PATHS - 1) * 0.01));
        double pnlAt99 = simulatedPnL[index99];

        return (pnlAt99 < 0) ? -pnlAt99 : 0.0;
    }
}
