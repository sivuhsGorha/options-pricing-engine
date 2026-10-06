package com.sbk.optionspricer.models.tree;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

/**
 * Recombining trinomial tree pricer for American options (continuous dividend yield; no discrete dividends).
 *
 * <p>Memory is O(steps): the option values live in one reused 1D array and the node spots in another,
 * computed once as powers of the up factor (they were recomputed with Math.pow for every node at every step,
 * which made the pricer quadratic in transcendental calls). Each call allocates those two arrays.
 */
public class AmericanTreePricer {

    /**
     * Prices an American option utilizing a Trinomial Tree backward induction scheme.
     * Early exercise is checked at each node.
     *
     * @param type  CALL or PUT
     * @param p     Option parameters (Spot, Strike, Time, Rate, Vol, Div)
     * @param steps Number of time steps (higher = more accurate, slower)
     * @return The fair value of the American option
     */
    public static double price(OptionType type, OptionParameters p, int steps) {
        if (p.timeToExpiry() <= 1e-10 || p.volatility() <= 1e-10) {
            return intrinsicValue(type, p.spot(), p.strike());
        }

        TrinomialTreeParameters treeParams = TrinomialTreeParameters.calculate(p, steps);
        
        // Number of terminal nodes is 2 * steps + 1
        int numNodes = 2 * steps + 1;

        // Node spots S0 * u^j for j = -steps..steps, built by repeated multiplication (a node at net j jumps has the
        // same spot at every time level, so one table serves the whole tree).
        double[] spots = new double[numNodes];
        spots[steps] = p.spot();
        for (int j = 1; j <= steps; j++) {
            spots[steps + j] = spots[steps + j - 1] * treeParams.upFactor();
            spots[steps - j] = spots[steps - j + 1] * treeParams.downFactor();
        }
        
        // Single 1D array to track option values at the current time step.
        // Avoids allocating massive 2D structures (O(N) memory instead of O(N^2)).
        double[] values = new double[numNodes];
        
        // 1. Initialize terminal payoff at t = T
        for (int i = 0; i < numNodes; i++) {
            // Node index 'i' corresponds to net up-jumps
            // At bottom node (i=0), we had 'steps' down-jumps and 'steps' left-jumps (wait, it's 2*steps down relative to max up)
            // Let j = i - steps. Thus j ranges from -steps to +steps.
            values[i] = intrinsicValue(type, spots[i], p.strike());
        }

        // 2. Backward induction through the tree
        for (int step = steps - 1; step >= 0; step--) {
            // At 'step', there are 2 * step + 1 valid nodes.
            for (int i = 0; i <= 2 * step; i++) {
                // Expected continuation value discounted back one step
                double continuationValue = treeParams.discountFactor() * (
                        treeParams.pDown() * values[i] +
                        treeParams.pMid() * values[i + 1] +
                        treeParams.pUp() * values[i + 2]
                );

                // Check early exercise premium
                // Node i at this level has net jump j = i - step, i.e. table index steps + (i - step).
                double earlyExerciseValue = intrinsicValue(type, spots[steps - step + i], p.strike());

                // American option value is max(continuation, early exercise)
                values[i] = Math.max(continuationValue, earlyExerciseValue);
            }
        }

        // The root node (i=0) now contains the present value of the option at t=0
        return values[0];
    }

    private static double intrinsicValue(OptionType type, double spot, double strike) {
        return type == OptionType.CALL 
                ? Math.max(spot - strike, 0.0) 
                : Math.max(strike - spot, 0.0);
    }
}
