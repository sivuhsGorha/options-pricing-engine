package com.sbk.optionspricer.models.pde;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

/**
 * 1D Finite Difference PDE Solver using the Crank-Nicolson scheme.
 * Supports both European and American (early-exercise via Brennan-Schwartz) options.
 *
 * Formulated on a log-spot (x = ln(S)) grid to ensure constant coefficients
 * and stable tridiagonal inversion.
 */
public class CrankNicolsonPricer {

    /**
     * @param type       CALL or PUT
     * @param p          Option parameters
     * @param spaceSteps Number of spatial grid points (N)
     * @param timeSteps  Number of time grid points (M)
     * @param isAmerican True to apply early exercise boundary conditions at each step
     * @return Option price at S = spot
     */
    public static double price(OptionType type, OptionParameters p, int spaceSteps, int timeSteps, boolean isAmerican) {
        if (p.timeToExpiry() <= 1e-10 || p.volatility() <= 1e-10) {
            return intrinsic(type, p.spot(), p.strike());
        }

        int N = spaceSteps;
        int M = timeSteps;
        
        double dt = p.timeToExpiry() / M;
        double vol = p.volatility();
        
        // Grid bounds: +/- 4 standard deviations from the forward
        double center = Math.log(p.spot());
        double stdDev = vol * Math.sqrt(p.timeToExpiry());
        double xMin = center - 4.0 * stdDev;
        double xMax = center + 4.0 * stdDev;
        double dx = (xMax - xMin) / N;
        
        double[] x = new double[N + 1];
        double[] S = new double[N + 1];
        double[] V = new double[N + 1]; // Option values at current time step
        
        // Initialize grid and terminal payoff at t = T
        for (int i = 0; i <= N; i++) {
            x[i] = xMin + i * dx;
            S[i] = Math.exp(x[i]);
            V[i] = intrinsic(type, S[i], p.strike());
        }
        
        // PDE Coefficients for log-grid
        double nu = p.riskFreeRate() - p.dividendYield() - 0.5 * vol * vol;
        double alpha = (vol * vol * dt) / (4.0 * dx * dx);
        double beta = (nu * dt) / (4.0 * dx);
        
        // Implicit matrix A diagonals
        double[] a = new double[N + 1];
        double[] b = new double[N + 1];
        double[] c = new double[N + 1];
        
        // Right hand side vector (which will be overwritten by Thomas solver)
        double[] Z = new double[N + 1];
        double[] cPrime = new double[N + 1]; // scratch array
        
        double rDtHalf = 0.5 * p.riskFreeRate() * dt;
        
        double A_lower = -alpha + beta;
        double A_main  = 1.0 + 2.0 * alpha + rDtHalf;
        double A_upper = -alpha - beta;
        
        double B_lower = alpha - beta;
        double B_main  = 1.0 - 2.0 * alpha - rDtHalf;
        double B_upper = alpha + beta;

        // Step backwards in time
        for (int m = M - 1; m >= 0; m--) {
            double tau = p.timeToExpiry() - (m * dt); // Time to maturity from current step
            
            // Build RHS vector Z = B * V
            for (int i = 1; i < N; i++) {
                Z[i] = B_lower * V[i - 1] + B_main * V[i] + B_upper * V[i + 1];
                
                // Set constant tridiagonal bands for the inner nodes
                a[i] = A_lower;
                b[i] = A_main;
                c[i] = A_upper;
            }
            
            // Apply Dirichlet boundary conditions (discounted intrinsic values)
            double sMin = S[0];
            double sMax = S[N];
            
            if (type == OptionType.CALL) {
                // Lower boundary: Call is worthless as S -> 0
                Z[0] = 0.0;
                // Upper boundary: Call ~ S*e^(-q*tau) - K*e^(-r*tau)
                Z[N] = sMax * Math.exp(-p.dividendYield() * tau) - p.strike() * Math.exp(-p.riskFreeRate() * tau);
            } else {
                // Lower boundary: Put ~ K*e^(-r*tau) - S*e^(-q*tau)
                Z[0] = p.strike() * Math.exp(-p.riskFreeRate() * tau) - sMin * Math.exp(-p.dividendYield() * tau);
                // Upper boundary: Put is worthless as S -> infinity
                Z[N] = 0.0;
            }
            
            // Fix boundary rows in the tridiagonal matrix to represent Z_0 = value and Z_N = value
            b[0] = 1.0; c[0] = 0.0; 
            a[N] = 0.0; b[N] = 1.0;
            
            // Solve A * V_new = Z
            ThomasAlgorithm.solve(a, b, c, Z, N + 1, cPrime);
            
            // Update V and apply American early-exercise constraint (Brennan-Schwartz)
            for (int i = 0; i <= N; i++) {
                V[i] = Z[i];
                if (isAmerican) {
                    V[i] = Math.max(V[i], intrinsic(type, S[i], p.strike()));
                }
            }
        }
        
        // Linear interpolation to find the value exactly at S_spot
        for (int i = 0; i < N; i++) {
            if (center >= x[i] && center <= x[i + 1]) {
                double weight = (center - x[i]) / dx;
                return (1.0 - weight) * V[i] + weight * V[i + 1];
            }
        }
        
        return V[N / 2]; // Fallback to center node
    }
    
    private static double intrinsic(OptionType type, double s, double k) {
        return type == OptionType.CALL ? Math.max(s - k, 0.0) : Math.max(k - s, 0.0);
    }
}
