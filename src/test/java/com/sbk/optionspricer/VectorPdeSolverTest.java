package com.sbk.optionspricer;

import com.sbk.optionspricer.models.pde.VectorPdeSolver;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class VectorPdeSolverTest {

    @Test
    void testVectorVsScalarMaxError() {
        double spot = 100.0;
        int size = 256;
        double[] strikes = new double[size];
        for (int i = 0; i < size; i++) strikes[i] = 50.0 + i * 0.5;
        double timeToExpiry = 1.0;
        double rate = 0.05;
        double vol = 0.20;
        
        double[] vectorPrices = new double[size];
        VectorPdeSolver.priceBatchPdeVectorized(true, spot, strikes, timeToExpiry, rate, vol, vectorPrices);
        
        double maxError = 0.0;
        for (int i = 0; i < size; i++) {
            OptionParameters params = new OptionParameters(spot, strikes[i], timeToExpiry, rate, vol, 0.0);
            double scalarPrice = com.sbk.optionspricer.models.pde.DiscreteDividendPricer.price(
                OptionType.CALL, params, null, 150, 150, true);
            double error = Math.abs(vectorPrices[i] - scalarPrice);
            if (error > maxError) maxError = error;
        }
        
        System.out.println("Vector vs Scalar Max Error: " + maxError);
        assertEquals(0.0, maxError, 1e-9, "Vector implementation must exactly match scalar implementation");
    }
}
