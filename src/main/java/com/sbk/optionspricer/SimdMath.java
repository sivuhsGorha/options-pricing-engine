package com.sbk.optionspricer;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;
import jdk.incubator.vector.VectorOperators;

/**
 * Bulk array math. {@code sqrt}, {@code add} (and the other element-wise arithmetic) use the incubator Vector
 * API. {@code exp} and {@code log} are NOT vectorized: they loop over {@link FastMath} approximations, whose
 * errors (1e-7 to 1e-6) are documented there. Every method gives the same result for an element whether it falls
 * in the vector body or in the scalar tail.
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

        // Scalar tail: the same exact IEEE sqrt as the vector body, so a result does not depend on the array length.
        for (; i < length; i++) {
            dst[i] = Math.sqrt(src[i]);
        }
    }

    /**
     * exp for every element via {@link FastMath#fastExp} (relative error up to ~1.6e-7). Scalar loop, not SIMD.
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
     * Natural log for every element via {@link FastMath#fastLog} (absolute error up to ~1.1e-6). Scalar loop, not SIMD.
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
