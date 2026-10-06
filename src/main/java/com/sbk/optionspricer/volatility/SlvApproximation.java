package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.FastMath;
import com.sbk.optionspricer.NormalDistribution;

import java.util.OptionalDouble;

/**
 * Stochastic Local Volatility (SLV) building blocks.
 * Combines Heston stochastic-volatility parameters with a Dupire local-volatility leverage function
 * sigma(t, S). Dupire local volatility is only defined for an arbitrage-free implied surface; this class
 * does not enforce that, it detects violations (see {@link #computeDupireLocalVol}) and reports them.
 *
 * SDE Dynamics:
 * dS_t = r S_t dt + sigma(t, S_t) * sqrt(v_t) * S_t * dW_t^S
 * dv_t = kappa * (theta - v_t) dt + xi * sqrt(v_t) * dW_t^v
 */
public class SlvApproximation {

    public static class SlvParams {
        public final double kappa; // Mean reversion rate
        public final double theta; // Long-term variance
        public final double xi;    // Volatility of volatility
        public final double rho;   // Correlation between spot and variance
        public final double v0;    // Initial variance

        public SlvParams(double kappa, double theta, double xi, double rho, double v0) {
            this.kappa = kappa;
            this.theta = theta;
            this.xi = xi;
            this.rho = rho;
            this.v0 = v0;
        }
    }

    /**
     * Dupire local volatility at (T, K) from an implied-volatility surface and its derivatives.
     *
     * <p>With C = BS(S, K, T, sigma(T, K), r, q) and total derivatives taken through the surface,
     * <pre>
     *   sigma_loc^2 = [ C_T + (r - q) K C_K + q C ] / [ 0.5 K^2 C_KK ]
     *   C_T  = theta_BS + V sigma_T
     *   C_K  = -e^(-rT) N(d2) + V sigma_K
     *   C_KK = e^(-rT) phi(d2) / (K sigma sqrt T) + 2 V d1/(K sigma sqrt T) sigma_K + V d1 d2 / sigma sigma_K^2 + V sigma_KK
     * </pre>
     * where V = S e^(-qT) phi(d1) sqrt T is the Black-Scholes vega.
     *
     * @return the local volatility, or empty when the surface admits none: a non-positive denominator is a
     *         butterfly arbitrage (negative implied density) and a non-positive numerator a calendar arbitrage.
     *         Earlier versions silently substituted the implied vol, hiding the arbitrage.
     */
    public static OptionalDouble computeDupireLocalVol(double spot, double strike, double expiry, double rate, double dividendYield,
                                                       double impliedVol, double dVoldT, double dVoldK, double d2VoldK2) {
        if (!Double.isFinite(spot) || spot <= 0.0) throw new IllegalArgumentException("spot must be finite and positive");
        if (!Double.isFinite(strike) || strike <= 0.0) throw new IllegalArgumentException("strike must be finite and positive");
        if (!Double.isFinite(expiry)) throw new IllegalArgumentException("expiry must be finite");
        if (!Double.isFinite(rate) || !Double.isFinite(dividendYield)) throw new IllegalArgumentException("rate and dividendYield must be finite");
        if (!Double.isFinite(impliedVol) || impliedVol <= 0.0) throw new IllegalArgumentException("impliedVol must be finite and positive");
        if (!Double.isFinite(dVoldT) || !Double.isFinite(dVoldK) || !Double.isFinite(d2VoldK2)) {
            throw new IllegalArgumentException("volatility derivatives must be finite");
        }
        if (expiry <= 1e-6) {
            return OptionalDouble.of(impliedVol); // at expiry the local vol is the implied vol
        }

        double sqrtT = Math.sqrt(expiry);
        double sigmaSqrtT = impliedVol * sqrtT;
        double d1 = (Math.log(spot / strike) + (rate - dividendYield + 0.5 * impliedVol * impliedVol) * expiry) / sigmaSqrtT;
        double d2 = d1 - sigmaSqrtT;
        double discountedSpot = spot * Math.exp(-dividendYield * expiry);
        double discountedStrike = strike * Math.exp(-rate * expiry);
        double pdf1 = NormalDistribution.pdf(d1);
        double pdf2 = NormalDistribution.pdf(d2);
        double cdf1 = NormalDistribution.cdf(d1);
        double cdf2 = NormalDistribution.cdf(d2);

        double vega = discountedSpot * pdf1 * sqrtT;
        double callPrice = discountedSpot * cdf1 - discountedStrike * cdf2;

        // Black-Scholes partials at fixed sigma.
        double thetaT = discountedSpot * pdf1 * impliedVol / (2.0 * sqrtT) - dividendYield * discountedSpot * cdf1
                + rate * discountedStrike * cdf2;
        double kPartial = -Math.exp(-rate * expiry) * cdf2;
        double kkPartial = Math.exp(-rate * expiry) * pdf2 / (strike * sigmaSqrtT);

        // Total derivatives through the implied-vol surface.
        double cT = thetaT + vega * dVoldT;
        double cK = kPartial + vega * dVoldK;
        double cKK = kkPartial
                + 2.0 * vega * d1 / (strike * sigmaSqrtT) * dVoldK
                + vega * d1 * d2 / impliedVol * dVoldK * dVoldK
                + vega * d2VoldK2;

        double numerator = cT + (rate - dividendYield) * strike * cK + dividendYield * callPrice;
        double denominator = 0.5 * strike * strike * cKK;
        if (!(numerator > 0.0) || !(denominator > 0.0)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.sqrt(numerator / denominator));
    }

    /**
     * Computes the SLV Leverage Function scale factor sigma_SLV(T, K) = sigma_Dupire(T, K) / sqrt(E[v_t | S_t = K]).
     */
    public static double computeLeverageFactor(double dupireVol, SlvParams heston, double expiry) {
        // Expected Heston variance E[v_t] at time T
        double expKappaT = FastMath.fastExp(-heston.kappa * expiry);
        double expectedVariance = heston.theta + (heston.v0 - heston.theta) * expKappaT;
        double expectedVol = FastMath.fastSqrt(Math.max(expectedVariance, 1e-6));

        double leverage = dupireVol / expectedVol;
        // Clamp leverage ratio to preserve numerical stability
        return Math.max(0.2, Math.min(leverage, 5.0));
    }
}
