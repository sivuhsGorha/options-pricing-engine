package com.sbk.optionspricer;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Java 21 Incubator Vector API (Parallel) implementation for European Option Pricing.
 * Prototyping Parallel lane-based pricing.
 */
public final class VectorBlackScholesPricer {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    private VectorBlackScholesPricer() {}

    public static double[] priceBatchParallel(double spot, double[] strikes, double timeToExpiry,
                                              double riskFreeRate, double volatility, boolean isCall) {
        return java.util.stream.IntStream.range(0, strikes.length).parallel().mapToDouble(i -> 
            BlackScholesPricer.price(isCall ? OptionType.CALL : OptionType.PUT, spot, strikes[i], timeToExpiry, riskFreeRate, volatility, 0.0)
        ).toArray();
    }

    public static void priceBatchPreallocated(double spot, double[] strikes, double timeToExpiry, double riskFreeRate, double volatility, boolean isCall, double[] out) {
        for (int i = 0; i < strikes.length; i++) {
            out[i] = BlackScholesPricer.price(isCall ? OptionType.CALL : OptionType.PUT, spot, strikes[i], timeToExpiry, riskFreeRate, volatility, 0.0);
        }
    }

    public static double[] priceBatchParallelSingleThread(double spot, double[] strikes, double timeToExpiry,
                                                      double riskFreeRate, double volatility, boolean isCall) {
        if (timeToExpiry <= 1e-10 || volatility <= 1e-10) {
            double[] out = new double[strikes.length];
            for (int i = 0; i < strikes.length; i++) {
                out[i] = BlackScholesPricer.price(isCall ? OptionType.CALL : OptionType.PUT, spot, strikes[i], timeToExpiry, riskFreeRate, volatility, 0.0);
            }
            return out;
        }

        double[] out = new double[strikes.length];
        int upperBound = SPECIES.loopBound(strikes.length);
        int i = 0;

        DoubleVector vSpot = DoubleVector.broadcast(SPECIES, spot);
        DoubleVector vT = DoubleVector.broadcast(SPECIES, timeToExpiry);
        DoubleVector vR = DoubleVector.broadcast(SPECIES, riskFreeRate);
        DoubleVector vVol = DoubleVector.broadcast(SPECIES, volatility);
        
        DoubleVector vVolSqrtT = vVol.mul(Math.sqrt(timeToExpiry));
        DoubleVector vRPlusHalfVol2 = DoubleVector.broadcast(SPECIES, riskFreeRate + 0.5 * volatility * volatility);

        double discountR = Math.exp(-riskFreeRate * timeToExpiry);
        DoubleVector vDiscR = DoubleVector.broadcast(SPECIES, discountR);

        for (; i < upperBound; i += SPECIES.length()) {
            DoubleVector vK = DoubleVector.fromArray(SPECIES, strikes, i);

            // d1 = (log(S/K) + (r + vol^2 / 2)*T) / (vol*sqrt(T))
            DoubleVector vS_div_K = vSpot.div(vK);
            DoubleVector vLog = vS_div_K.lanewise(VectorOperators.LOG);
            DoubleVector vNum = vLog.add(vRPlusHalfVol2.mul(vT));
            DoubleVector vD1 = vNum.div(vVolSqrtT);
            DoubleVector vD2 = vD1.sub(vVolSqrtT);

            DoubleVector cdfD1;
            DoubleVector cdfD2;
            if (isCall) {
                cdfD1 = vectorCdf(vD1);
                cdfD2 = vectorCdf(vD2);
                DoubleVector term1 = vSpot.mul(cdfD1);
                DoubleVector term2 = vK.mul(vDiscR).mul(cdfD2);
                term1.sub(term2).intoArray(out, i);
            } else {
                cdfD1 = vectorCdf(vD1.neg());
                cdfD2 = vectorCdf(vD2.neg());
                DoubleVector term1 = vK.mul(vDiscR).mul(cdfD2);
                DoubleVector term2 = vSpot.mul(cdfD1);
                term1.sub(term2).intoArray(out, i);
            }
        }

        // Tail
        for (; i < strikes.length; i++) {
            out[i] = BlackScholesPricer.price(isCall ? OptionType.CALL : OptionType.PUT, spot, strikes[i], timeToExpiry, riskFreeRate, volatility, 0.0);
        }
        return out;
    }

    /**
     * Phi for every lane, using the same double-precision algorithm as {@link NormalDistribution#cdf}.
     * The previous lane-wise A&S approximation was fast but differed from the scalar pricer by ~1e-5, so
     * the SIMD and scalar paths could disagree on a price. The CDF is not vectorizable without that loss
     * (it branches and iterates per value); the vector work here is the log and the arithmetic around it.
     */
    static DoubleVector vectorCdf(DoubleVector x) {
        double[] lanes = new double[SPECIES.length()];
        x.intoArray(lanes, 0);
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = NormalDistribution.cdf(lanes[i]);
        }
        return DoubleVector.fromArray(SPECIES, lanes, 0);
    }
}
