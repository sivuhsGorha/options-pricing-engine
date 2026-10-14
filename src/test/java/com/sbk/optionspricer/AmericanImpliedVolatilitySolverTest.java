package com.sbk.optionspricer;

import com.sbk.optionspricer.models.tree.AmericanTreePricer;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

public class AmericanImpliedVolatilitySolverTest {

    private static final double SPOT = 100.0;
    private static final double T = 0.25; // 3 months
    private static final double R = 0.05;
    private static final double Q = 0.0;

    @Test
    void otmOptionMatchesEuropeanInversionDirectly() {
        // OTM Call: Strike 110, Spot 100
        double strike = 110.0;
        double trueVol = 0.20;
        double price = BlackScholesPricer.price(OptionType.CALL, SPOT, strike, T, R, trueVol, Q);

        OptionalDouble iv = AmericanImpliedVolatilitySolver.solve(OptionType.CALL, SPOT, strike, T, R, Q, price);
        assertTrue(iv.isPresent());
        assertEquals(trueVol, iv.getAsDouble(), 1e-5, "OTM call has zero early exercise premium; exact match with European");
    }

    @Test
    void itmAmericanPutRecoversTrueVolatilityWithoutEuropeanEarlyExerciseInflation() {
        // Deep ITM Put: Strike 115, Spot 100 (Intrinsic = $15.00)
        double strike = 115.0;
        double trueVol = 0.25;
        OptionParameters p = new OptionParameters(SPOT, strike, T, R, trueVol, Q);

        double americanPrice = AmericanTreePricer.price(OptionType.PUT, p, 100);
        double europeanPrice = BlackScholesPricer.price(OptionType.PUT, SPOT, strike, T, R, trueVol, Q);

        // Verify American price exceeds European price due to early exercise optionality on cash
        assertTrue(americanPrice > europeanPrice, "American put must trade above European put due to early exercise premium");

        // European inversion on American price artificially inflates vol:
        OptionalDouble europeanIv = ImpliedVolatilitySolver.solve(OptionType.PUT, SPOT, strike, T, R, Q, americanPrice, new double[5]);
        assertTrue(europeanIv.isPresent());
        assertTrue(europeanIv.getAsDouble() > trueVol + 0.02,
                "European formula inflates IV from " + trueVol + " to " + europeanIv.getAsDouble());

        // American solver correctly recovers true vol:
        OptionalDouble americanIv = AmericanImpliedVolatilitySolver.solve(OptionType.PUT, SPOT, strike, T, R, Q, americanPrice);
        assertTrue(americanIv.isPresent());
        assertEquals(trueVol, americanIv.getAsDouble(), 0.005,
                "American tree solver accurately extracts true 25% volatility without early exercise distortion");
    }

    @Test
    void itmAmericanCallWithDividendsRecoversTrueVolatility() {
        // ITM Call with dividend: Strike 90, Spot 100, Q = 0.04
        double strike = 90.0;
        double trueVol = 0.22;
        double divYield = 0.04;
        OptionParameters p = new OptionParameters(SPOT, strike, T, R, trueVol, divYield);

        double americanPrice = AmericanTreePricer.price(OptionType.CALL, p, 100);
        OptionalDouble americanIv = AmericanImpliedVolatilitySolver.solve(OptionType.CALL, SPOT, strike, T, R, divYield, americanPrice);

        assertTrue(americanIv.isPresent());
        assertEquals(trueVol, americanIv.getAsDouble(), 0.005, "American solver recovers true 22% volatility for ITM call");
    }
}
