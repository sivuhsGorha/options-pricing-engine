package com.sbk.optionspricer;

import com.sbk.optionspricer.models.pde.VectorPdeSolver;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class VectorPdeSolverTest {

    @Test
    void testVectorPdeSolverMatchesBlackScholes() {
        double spot = 100.0;
        double[] strikes = new double[]{90.0};
        double timeToExpiry = 1.0;
        double rate = 0.05;
        double vol = 0.20;
        double[] pricesOut = new double[1];

        // Call VectorPdeSolver with (isCall=true, spot=100, strikes=[90], T=1, rate=0.05, vol=0.20)
        VectorPdeSolver.priceBatchPdeVectorized(true, spot, strikes, timeToExpiry, rate, vol, pricesOut);

        OptionParameters params = OptionParameters.noDividend(spot, 90.0, timeToExpiry, rate, vol);
        double bsmCallPrice = BlackScholesPricer.price(OptionType.CALL, params);

        // BSM Call price for these parameters is ~16.6994
        assertEquals(16.6994, bsmCallPrice, 1e-4, "BSM Call price should be 16.6994");

        // VectorPdeSolver price must match BSM within 1e-2 tolerance
        assertEquals(bsmCallPrice, pricesOut[0], 1e-2,
                "VectorPdeSolver price (" + pricesOut[0] + ") must match BSM (" + bsmCallPrice + ") within 1e-2");
    }
}
