package com.sbk.optionspricer;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Java 21 Incubator Vector API (SIMD) implementation for European Option Pricing.
 * Prototyping SIMD lane-based pricing.
 */
public final class VectorBlackScholesPricer {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    private static final double A1 = 0.254829592;
    private static final double A2 = -0.284496736;
    private static final double A3 = 1.421413741;
    private static final double A4 = -1.453152027;
    private static final double A5 = 1.061405429;
    private static final double P = 0.3275911;

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

    public static double[] priceBatchSimdSingleThread(double spot, double[] strikes, double timeToExpiry,
                                                      double riskFreeRate, double volatility, boolean isCall) {
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

    static DoubleVector vectorCdf(DoubleVector x) {
        DoubleVector sqrt2 = DoubleVector.broadcast(SPECIES, Math.sqrt(2.0));
        DoubleVector erfVal = vectorErf(x.div(sqrt2));
        DoubleVector one = DoubleVector.broadcast(SPECIES, 1.0);
        DoubleVector half = DoubleVector.broadcast(SPECIES, 0.5);
        return one.add(erfVal).mul(half);
    }

    static DoubleVector vectorErf(DoubleVector x) {
        VectorMask<Double> isNeg = x.lt(0.0);
        DoubleVector absX = x.lanewise(VectorOperators.ABS);

        DoubleVector one = DoubleVector.broadcast(SPECIES, 1.0);
        DoubleVector p = DoubleVector.broadcast(SPECIES, P);
        DoubleVector t = one.div(one.add(p.mul(absX)));

        DoubleVector a1 = DoubleVector.broadcast(SPECIES, A1);
        DoubleVector a2 = DoubleVector.broadcast(SPECIES, A2);
        DoubleVector a3 = DoubleVector.broadcast(SPECIES, A3);
        DoubleVector a4 = DoubleVector.broadcast(SPECIES, A4);
        DoubleVector a5 = DoubleVector.broadcast(SPECIES, A5);

        // a5*t + a4
        DoubleVector poly = a5.mul(t).add(a4);
        poly = poly.mul(t).add(a3);
        poly = poly.mul(t).add(a2);
        poly = poly.mul(t).add(a1);

        DoubleVector expTerm = absX.mul(absX).neg().lanewise(VectorOperators.EXP);
        DoubleVector y = one.sub(poly.mul(t).mul(expTerm));

        DoubleVector negY = y.neg();
        return y.blend(negY, isNeg);
    }
}
