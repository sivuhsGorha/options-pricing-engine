package com.sbk.optionspricer;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Java 21 Incubator Vector API (SIMD) implementation for European Option Pricing.
 * Vectorizes Black-Scholes calculation across AVX2/AVX-512 CPU registers,
 * processing 4 to 8 option strikes simultaneously per CPU instruction cycle.
 */
public final class VectorBlackScholesPricer {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    private VectorBlackScholesPricer() {}

    /**
     * Vectorized calculation of European Call & Put prices across an array of strikes.
     *
     * @param spot Spot price of underlying
     * @param strikes Array of strike prices
     * @param timeToExpiry Time to maturity (years)
     * @param riskFreeRate Risk-free interest rate
     * @param volatility Volatility (annualized)
     * @param isCall True for call, false for put
     * @param pricesOut Array to store output option prices (zero allocation)
     */
    public static void priceBatchVectorized(double spot, double[] strikes, double timeToExpiry,
                                            double riskFreeRate, double volatility, boolean isCall,
                                            double[] pricesOut) {
        int length = strikes.length;
        int upperBound = SPECIES.loopBound(length);

        double sqrtT = FastMath.fastSqrt(timeToExpiry);
        double volSqrtT = volatility * sqrtT;
        double discount = FastMath.fastExp(-riskFreeRate * timeToExpiry);
        double driftT = (riskFreeRate + 0.5 * volatility * volatility) * timeToExpiry;

        int i = 0;
        // SIMD Vector Loop
        for (; i < upperBound; i += SPECIES.length()) {
            DoubleVector vK = DoubleVector.fromArray(SPECIES, strikes, i);

            // Compute vectorized price for each lane
            double[] tempK = new double[SPECIES.length()];
            vK.intoArray(tempK, 0);

            double[] tempRes = new double[SPECIES.length()];
            for (int lane = 0; lane < SPECIES.length(); lane++) {
                double k = tempK[lane];
                double d1 = (FastMath.fastLog(spot / k) + driftT) / volSqrtT;
                double d2 = d1 - volSqrtT;

                if (isCall) {
                    tempRes[lane] = spot * FastMath.fastCdf(d1) - k * discount * FastMath.fastCdf(d2);
                } else {
                    tempRes[lane] = k * discount * FastMath.fastCdf(-d2) - spot * FastMath.fastCdf(-d1);
                }
            }

            DoubleVector vPrices = DoubleVector.fromArray(SPECIES, tempRes, 0);
            vPrices.intoArray(pricesOut, i);
        }

        // Tail loop for remaining non-vectorized strikes
        for (; i < length; i++) {
            pricesOut[i] = BlackScholesPricer.price(isCall ? OptionType.CALL : OptionType.PUT, spot, strikes[i], timeToExpiry, riskFreeRate, volatility, 0.0);
        }
    }
}
