package com.sbk.optionspricer;

import java.util.Random;

/**
 * Monte Carlo pricer for European options, simulating terminal asset prices
 * under geometric Brownian motion and averaging the discounted payoff.
 *
 * This exists primarily as an independent numerical cross-check against
 * {@link BlackScholesPricer}: since both methods price the exact same
 * option under the exact same assumptions using entirely different
 * techniques (closed-form calculus vs. random simulation), if they agree
 * (within simulation error) it's real evidence the closed-form
 * implementation is correct — not just internally consistent with itself.
 */
public class MonteCarloPricer {

    /**
     * @param type       CALL or PUT
     * @param p          option parameters
     * @param numPaths   number of simulated price paths (more paths = less
     *                   simulation noise, at the cost of runtime)
     * @param seed       RNG seed, for reproducible results across runs
     * @return the estimated price, plus the standard error of that estimate
     */
    public static PricingResult price(OptionType type, OptionParameters p, int numPaths, long seed) {
        Random rng = new Random(seed);
        double drift = (p.riskFreeRate() - p.dividendYield() - 0.5 * p.volatility() * p.volatility()) * p.timeToExpiry();
        double diffusion = p.volatility() * Math.sqrt(p.timeToExpiry());
        double discount = Math.exp(-p.riskFreeRate() * p.timeToExpiry());

        double sumPayoff = 0.0;
        double sumPayoffSquared = 0.0;

        for (int i = 0; i < numPaths; i++) {
            double z = rng.nextGaussian();
            double terminalPrice = p.spot() * Math.exp(drift + diffusion * z);
            double payoff = (type == OptionType.CALL)
                    ? Math.max(terminalPrice - p.strike(), 0.0)
                    : Math.max(p.strike() - terminalPrice, 0.0);
            double discountedPayoff = discount * payoff;

            sumPayoff += discountedPayoff;
            sumPayoffSquared += discountedPayoff * discountedPayoff;
        }

        double mean = sumPayoff / numPaths;
        double variance = (sumPayoffSquared / numPaths) - (mean * mean);
        double standardError = Math.sqrt(Math.max(variance, 0) / numPaths);

        return new PricingResult(mean, standardError, numPaths);
    }

    public record PricingResult(double price, double standardError, int numPaths) {
        /** Approximate 95% confidence interval half-width. */
        public double confidenceInterval95() {
            return 1.96 * standardError;
        }
    }
}
