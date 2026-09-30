package com.sbk.optionspricer.models.pde;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

/**
 * 1D Finite Difference PDE solver with Discrete Cash Dividend Jump Conditions.
 * Accurately models American and European options on dividend-paying stocks where
 * fixed cash dividends $D_k$ are paid at discrete ex-dividend times $t_k$.
 * 
 * At each ex-dividend date t_k, the grid undergoes a boundary value jump:
 * V(t_k^-, S) = V(t_k^+, S - D_k)
 * evaluated via linear/spline interpolation across grid points.
 */
public class DiscreteDividendPricer {

    public static class DiscreteDividend {
        public final double timeToDividend; // Time from t=0 to ex-dividend date (years)
        public final double amount;         // Cash dividend amount D ($)

        public DiscreteDividend(double timeToDividend, double amount) {
            this.timeToDividend = timeToDividend;
            this.amount = amount;
        }
    }

    /**
     * Prices an option with discrete cash dividends using Crank-Nicolson PDE solver.
     */
    public static double price(OptionType type, OptionParameters p, DiscreteDividend[] dividends,
                                int spaceSteps, int timeSteps, boolean isAmerican) {
        if (p.timeToExpiry() <= 1e-10 || p.volatility() <= 1e-10) {
            return intrinsic(type, p.spot(), p.strike());
        }

        int N = spaceSteps;
        int M = timeSteps;
        double dt = p.timeToExpiry() / M;
        double vol = p.volatility();

        double center = Math.log(p.spot());
        double stdDev = vol * Math.sqrt(p.timeToExpiry());
        double xMin = center - 4.5 * stdDev;
        double xMax = center + 4.5 * stdDev;
        double dx = (xMax - xMin) / N;

        double[] x = new double[N + 1];
        double[] S = new double[N + 1];
        double[] V = new double[N + 1];

        for (int i = 0; i <= N; i++) {
            x[i] = xMin + i * dx;
            S[i] = Math.exp(x[i]);
            V[i] = intrinsic(type, S[i], p.strike());
        }

        double nu = p.riskFreeRate() - 0.5 * vol * vol;
        double alpha = (vol * vol * dt) / (4.0 * dx * dx);
        double beta = (nu * dt) / (4.0 * dx);

        double[] a = new double[N + 1];
        double[] b = new double[N + 1];
        double[] c = new double[N + 1];
        double[] Z = new double[N + 1];
        double[] cPrime = new double[N + 1];

        double rDtHalf = 0.5 * p.riskFreeRate() * dt;
        double A_lower = -alpha + beta;
        double A_main  = 1.0 + 2.0 * alpha + rDtHalf;
        double A_upper = -alpha - beta;

        double B_lower = alpha - beta;
        double B_main  = 1.0 - 2.0 * alpha - rDtHalf;
        double B_upper = alpha + beta;

        // Backward induction loop
        for (int m = M - 1; m >= 0; m--) {
            double tCurrent = m * dt; // Current time from t=0
            double tau = p.timeToExpiry() - tCurrent; // Time to expiry

            // Solve standard PDE step from t_{m+1} back to t_m
            for (int i = 1; i < N; i++) {
                Z[i] = B_lower * V[i - 1] + B_main * V[i] + B_upper * V[i + 1];
                a[i] = A_lower;
                b[i] = A_main;
                c[i] = A_upper;
            }

            double sMin = S[0];
            double sMax = S[N];

            if (type == OptionType.CALL) {
                Z[0] = 0.0;
                Z[N] = sMax - p.strike() * Math.exp(-p.riskFreeRate() * tau);
            } else {
                Z[0] = p.strike() * Math.exp(-p.riskFreeRate() * tau) - sMin;
                Z[N] = 0.0;
            }

            b[0] = 1.0; c[0] = 0.0;
            a[N] = 0.0; b[N] = 1.0;

            ThomasAlgorithm.solve(a, b, c, Z, N + 1, cPrime);

            for (int i = 0; i <= N; i++) {
                V[i] = Z[i];
            }

            // Check if a discrete dividend falls within (tCurrent - dt, tCurrent]
            if (dividends != null) {
                for (DiscreteDividend div : dividends) {
                    if (div.timeToDividend >= tCurrent && div.timeToDividend < tCurrent + dt) {
                        // Apply Dividend Jump Condition: V_jump(S) = Interpolated V(S - D)
                        applyDividendJump(S, V, div.amount, N);

                        // If American, evaluate early exercise right before dividend drop
                        if (isAmerican) {
                            for (int i = 0; i <= N; i++) {
                                V[i] = Math.max(V[i], intrinsic(type, S[i], p.strike()));
                            }
                        }
                    }
                }
            }

            if (isAmerican) {
                for (int i = 0; i <= N; i++) {
                    V[i] = Math.max(V[i], intrinsic(type, S[i], p.strike()));
                }
            }
        }

        // Interpolate at S = spot
        for (int i = 0; i < N; i++) {
            if (center >= x[i] && center <= x[i + 1]) {
                double weight = (center - x[i]) / dx;
                return (1.0 - weight) * V[i] + weight * V[i + 1];
            }
        }

        return V[N / 2];
    }

    private static void applyDividendJump(double[] S, double[] V, double divAmount, int N) {
        double[] tempV = new double[N + 1];
        for (int i = 0; i <= N; i++) {
            double sPostDiv = Math.max(S[i] - divAmount, S[0]);
            tempV[i] = interpolateLinear(S, V, sPostDiv, N);
        }
        System.arraycopy(tempV, 0, V, 0, N + 1);
    }

    private static double interpolateLinear(double[] S, double[] V, double sTarget, int N) {
        if (sTarget <= S[0]) return V[0];
        if (sTarget >= S[N]) return V[N];

        // Binary search or linear scan for segment
        int low = 0;
        int high = N;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (S[mid] < sTarget) {
                low = mid + 1;
            } else if (S[mid] > sTarget) {
                high = mid - 1;
            } else {
                return V[mid];
            }
        }

        int idx = high;
        if (idx < 0) idx = 0;
        if (idx >= N) idx = N - 1;

        double weight = (sTarget - S[idx]) / (S[idx + 1] - S[idx]);
        return (1.0 - weight) * V[idx] + weight * V[idx + 1];
    }

    private static double intrinsic(OptionType type, double s, double k) {
        return type == OptionType.CALL ? Math.max(s - k, 0.0) : Math.max(k - s, 0.0);
    }
}
