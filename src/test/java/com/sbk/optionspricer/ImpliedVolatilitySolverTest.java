package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ImpliedVolatilitySolverTest {

    @Test
    void testImpossiblePrice200Rejected() {
        // S=100, K=100, T=1, r=0.05, marketPrice=200 is impossible (max possible call price is S*exp(-qT) = 100)
        OptionParameters params = OptionParameters.noDividend(100.0, 100.0, 1.0, 0.05, 0.20);
        OptionalDouble result = ImpliedVolatilitySolver.solve(OptionType.CALL, params, 200.0);

        assertFalse(result.isPresent(), "Call priced at 200 with S=100 must be rejected with empty OptionalDouble");
    }

    @Test
    void testPriceBelowLowerBoundRejected() {
        // S=100, K=90, T=1, r=0.05 -> discounted lower bound is 100 - 90*exp(-0.05) = 14.3894
        // Market price 5.0 is below the lower bound
        OptionParameters params = OptionParameters.noDividend(100.0, 90.0, 1.0, 0.05, 0.20);
        OptionalDouble result = ImpliedVolatilitySolver.solve(OptionType.CALL, params, 5.0);

        assertFalse(result.isPresent(), "Call price below discounted lower bound must be rejected");
    }

    @Test
    void testNaNAndInfiniteInputsRejected() {
        OptionParameters validParams = OptionParameters.noDividend(100.0, 100.0, 1.0, 0.05, 0.20);

        assertFalse(ImpliedVolatilitySolver.solve(OptionType.CALL, validParams, Double.NaN).isPresent(), "NaN market price must be rejected");
        assertFalse(ImpliedVolatilitySolver.solve(OptionType.CALL, validParams, Double.POSITIVE_INFINITY).isPresent(), "Infinite market price must be rejected");
        assertFalse(ImpliedVolatilitySolver.solve(OptionType.CALL, 100.0, 100.0, Double.NaN, 0.05, 0.0, 10.0, new double[5]).isPresent(), "NaN expiry must be rejected");
    }

    @Test
    void testValidPriceRecoversVolatility() {
        double expectedVol = 0.25;
        OptionParameters params = OptionParameters.noDividend(100.0, 105.0, 0.5, 0.05, expectedVol);
        double bsPrice = BlackScholesPricer.price(OptionType.CALL, params);

        OptionalDouble result = ImpliedVolatilitySolver.solve(OptionType.CALL, params, bsPrice);

        assertTrue(result.isPresent(), "Valid price must return non-empty OptionalDouble");
        assertEquals(expectedVol, result.getAsDouble(), 1e-4, "Recovered implied vol must match expected vol");
    }
}
