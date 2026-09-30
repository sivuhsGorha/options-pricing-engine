package com.sbk.optionspricer.models.pde;

/**
 * Tridiagonal matrix algorithm (TDMA), also known as the Thomas algorithm.
 * Solves systems of equations where the matrix is tridiagonal: A * x = d
 *
 * This implementation is designed for the hot path: it takes pre-allocated
 * primitive arrays and performs zero memory allocations.
 */
public class ThomasAlgorithm {

    /**
     * Solves the tridiagonal system inplace.
     *
     * @param a lower diagonal (a[0] is unused)
     * @param b main diagonal
     * @param c upper diagonal (c[n-1] is unused)
     * @param d right-hand side vector (gets overwritten with the solution vector x)
     * @param n size of the system
     * @param cPrime scratch array of size n to store modified c coefficients (avoids allocation)
     */
    public static void solve(double[] a, double[] b, double[] c, double[] d, int n, double[] cPrime) {
        cPrime[0] = c[0] / b[0];
        d[0] = d[0] / b[0];

        // Forward sweep
        for (int i = 1; i < n; i++) {
            double denominator = b[i] - a[i] * cPrime[i - 1];
            if (i < n - 1) {
                cPrime[i] = c[i] / denominator;
            }
            d[i] = (d[i] - a[i] * d[i - 1]) / denominator;
        }

        // Back substitution (d is updated in place to become the solution vector x)
        for (int i = n - 2; i >= 0; i--) {
            d[i] = d[i] - cPrime[i] * d[i + 1];
        }
    }
}
