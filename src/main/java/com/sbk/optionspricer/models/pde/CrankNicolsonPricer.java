package com.sbk.optionspricer.models.pde;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

/**
 * Compatibility entry point for the Crank-Nicolson PDE pricer.
 *
 * <p>This used to be a second, separate implementation of the same finite-difference scheme (with the
 * defects described on {@link DiscreteDividendPricer}: it ignored nothing but still had negative boundary
 * values, no start-up smoothing and a post-hoc projection it mislabelled as Brennan-Schwartz). It now
 * delegates to the single solver, so there is one implementation to verify.
 *
 * @deprecated use {@link DiscreteDividendPricer#price} directly
 */
@Deprecated
public class CrankNicolsonPricer {

    /**
     * @param type       CALL or PUT
     * @param p          Option parameters (including the continuous dividend yield)
     * @param spaceSteps Number of spatial intervals (N), at least 4
     * @param timeSteps  Number of time steps (M), at least 1
     * @param isAmerican True to enforce early exercise
     * @return Option price at S = spot
     */
    public static double price(OptionType type, OptionParameters p, int spaceSteps, int timeSteps, boolean isAmerican) {
        return DiscreteDividendPricer.price(type, p, null, spaceSteps, timeSteps, isAmerican);
    }
}
