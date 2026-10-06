package com.sbk.optionspricer;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;
import jdk.incubator.vector.VectorOperators;

/**
 * SIMD Vectorized Mathematics Engine using jdk.incubator.vector.
 * Accelerates bulk math operations (sqrt, exp, log) via CPU AVX-512 / NEON instructions.
 */
public class SimdMath {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    /**
     * Computes the square root of all elements in the source array and writes to the destination.
     */
    public static void sqrt(double[] src, double[] dst, int length) {
        int i = 0;
        int upperBound = SPECIES.loopBound(length);
        
        for (; i < upperBound; i += SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(SPECIES, src, i);
            v.lanewise(VectorOperators.SQRT).intoArray(dst, i);
        }
        
        // Scalar fallback loop for tail elements
        for (; i < length; i++) {
            dst[i] = FastMath.fastSqrt(src[i]);
        }
    }

    /**
     * Vectorized exponentiation.
     * Note: VectorOperators.EXP is not standard on all architectures yet, so we apply 
     * a vectorized polynomial approximation (Taylor/Maclaurin) or fallback to FastMath.
     */
    public static void exp(double[] src, double[] dst, int length) {
        // Fallback to scalar FastMath.fastExp since bitwise Double-to-Long manipulations 
        // across vectors require complex re-interpretations that can bottleneck.
        // A full AVX-512 implementation would use SVML (Short Vector Math Library) bindings.
        for (int i = 0; i < length; i++) {
            dst[i] = FastMath.fastExp(src[i]);
        }
    }

    /**
     * Vectorized natural logarithm.
     */
    public static void log(double[] src, double[] dst, int length) {
        // Similar to exp, full cross-platform SIMD log requires SVML.
        // We fallback to scalar FastMath for precision and speed.
        for (int i = 0; i < length; i++) {
            dst[i] = FastMath.fastLog(src[i]);
        }
    }

    /**
     * Vectorized addition: dst = a + b
     */
    public static void add(double[] a, double[] b, double[] dst, int length) {
        int i = 0;
        int upperBound = SPECIES.loopBound(length);
        
        for (; i < upperBound; i += SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(SPECIES, a, i);
            DoubleVector vb = DoubleVector.fromArray(SPECIES, b, i);
            va.add(vb).intoArray(dst, i);
        }
        
        for (; i < length; i++) {
            dst[i] = a[i] + b[i];
        }
    }

    /**
     * Vectorized multiply: dst = a * b
     */
    public static void multiply(double[] a, double[] b, double[] dst, int length) {
        int i = 0;
        int upperBound = SPECIES.loopBound(length);
        
        for (; i < upperBound; i += SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(SPECIES, a, i);
            DoubleVector vb = DoubleVector.fromArray(SPECIES, b, i);
            va.mul(vb).intoArray(dst, i);
        }
        
        for (; i < length; i++) {
            dst[i] = a[i] * b[i];
        }
    }
}
