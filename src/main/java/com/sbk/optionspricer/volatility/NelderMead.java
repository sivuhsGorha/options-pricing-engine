package com.sbk.optionspricer.volatility;

import java.util.Arrays;
import java.util.function.ToDoubleFunction;

/**
 * Nelder-Mead downhill simplex minimiser for small, smooth, derivative-free problems (the surface fits have
 * three parameters). Standard coefficients: reflection 1, expansion 2, contraction 1/2, shrink 1/2.
 *
 * <p>An objective that returns NaN (an invalid parameter combination) is treated as +infinity, so the simplex
 * moves away from it instead of comparing against NaN. Convergence requires both the spread of function values
 * and the simplex diameter to fall below the tolerance; hitting the iteration cap returns the best point found
 * with {@code converged == false}.
 */
public final class NelderMead {

    public record Result(double[] point, double value, int iterations, boolean converged) {}

    private NelderMead() {
    }

    public static Result minimize(ToDoubleFunction<double[]> objective, double[] start, double[] step, int maxIterations, double tolerance) {
        if (objective == null || start == null || step == null) {
            throw new IllegalArgumentException("objective, start and step must not be null");
        }
        int n = start.length;
        if (n == 0 || step.length != n) {
            throw new IllegalArgumentException("start and step must have the same, non-zero length");
        }
        if (maxIterations < 1 || !(tolerance > 0.0)) {
            throw new IllegalArgumentException("maxIterations must be >= 1 and tolerance > 0");
        }

        double[][] x = new double[n + 1][];
        double[] fx = new double[n + 1];
        x[0] = start.clone();
        for (int i = 0; i < n; i++) {
            x[i + 1] = start.clone();
            x[i + 1][i] += step[i] == 0.0 ? 1e-3 : step[i];
        }
        for (int i = 0; i <= n; i++) {
            fx[i] = safe(objective, x[i]);
        }

        Integer[] order = new Integer[n + 1];
        int iterations = 0;
        boolean converged = false;
        while (iterations < maxIterations) {
            iterations++;
            for (int i = 0; i <= n; i++) order[i] = i;
            final double[] values = fx;
            Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
            int best = order[0], secondWorst = order[n - 1], worst = order[n];

            if (hasConverged(x, fx, best, worst, tolerance)) {
                converged = true;
                break;
            }

            double[] centroid = new double[n];
            for (int i = 0; i <= n; i++) {
                if (i == worst) continue;
                for (int d = 0; d < n; d++) centroid[d] += x[i][d] / n;
            }

            double[] reflected = combine(centroid, x[worst], 1.0);
            double fr = safe(objective, reflected);
            if (fr < fx[best]) {
                double[] expanded = combine(centroid, x[worst], 2.0);
                double fe = safe(objective, expanded);
                if (fe < fr) { x[worst] = expanded; fx[worst] = fe; } else { x[worst] = reflected; fx[worst] = fr; }
            } else if (fr < fx[secondWorst]) {
                x[worst] = reflected;
                fx[worst] = fr;
            } else {
                double[] contracted = fr < fx[worst] ? combine(centroid, x[worst], 0.5) : combine(centroid, x[worst], -0.5);
                double fc = safe(objective, contracted);
                if (fc < Math.min(fr, fx[worst])) {
                    x[worst] = contracted;
                    fx[worst] = fc;
                } else {
                    for (int i = 0; i <= n; i++) {
                        if (i == best) continue;
                        for (int d = 0; d < n; d++) x[i][d] = x[best][d] + 0.5 * (x[i][d] - x[best][d]);
                        fx[i] = safe(objective, x[i]);
                    }
                }
            }
        }

        int best = 0;
        for (int i = 1; i <= n; i++) if (fx[i] < fx[best]) best = i;
        return new Result(x[best].clone(), fx[best], iterations, converged);
    }

    /** centroid + factor * (centroid - worst): 1 reflects, 2 expands, 0.5 contracts outside, -0.5 contracts inside. */
    private static double[] combine(double[] centroid, double[] worst, double factor) {
        double[] out = new double[centroid.length];
        for (int d = 0; d < out.length; d++) out[d] = centroid[d] + factor * (centroid[d] - worst[d]);
        return out;
    }

    private static boolean hasConverged(double[][] x, double[] fx, int best, int worst, double tolerance) {
        if (!(fx[worst] - fx[best] <= tolerance * (1.0 + Math.abs(fx[best])))) {
            return false;
        }
        double scale = 0.0;
        for (double v : x[best]) scale = Math.max(scale, Math.abs(v));
        double maxDistance = 0.0;
        for (double[] vertex : x) {
            for (int d = 0; d < vertex.length; d++) maxDistance = Math.max(maxDistance, Math.abs(vertex[d] - x[best][d]));
        }
        return maxDistance <= Math.sqrt(tolerance) * (1.0 + scale);
    }

    private static double safe(ToDoubleFunction<double[]> objective, double[] point) {
        double v;
        try {
            v = objective.applyAsDouble(point);
        } catch (RuntimeException invalid) {
            return Double.POSITIVE_INFINITY;
        }
        return Double.isNaN(v) ? Double.POSITIVE_INFINITY : v;
    }
}
