package com.sbk.optionspricer.volatility;

/**
 * Surface SVI (SSVI) implied-volatility surface (Gatheral and Jacquier, 2014).
 *
 * <p>Total variance w(k, theta) is modeled as
 * <pre>  w(k, theta) = (theta / 2) * [ 1 + rho * phi(theta) * k + sqrt( (phi(theta) * k + rho)^2 + (1 - rho^2) ) ]</pre>
 * with k = ln(K / F) the log-moneyness against the <em>forward</em>, theta the at-the-money total variance, and
 * phi(theta) = eta / ( theta^gamma * (1 + theta)^(1 - gamma) ).
 *
 * <p>Arbitrage: this class does not "enforce" absence of arbitrage. It provides
 * <ul>
 *   <li>{@link SsviParams#satisfiesStaticNoArbitrageConditions()}: the sufficient conditions of the paper for no
 *       butterfly arbitrage, evaluated in closed form for this phi (valid for all theta at once);</li>
 *   <li>calendar-spread arbitrage cannot occur for this phi: the condition of Theorem 4.1 reduces to
 *       (1 - gamma)/(1 + theta) &lt;= (1 + sqrt(1 - rho^2))/rho^2, which holds because the left side is below 1 and
 *       the right side above 1; verified numerically in the tests;</li>
 *   <li>{@link #isArbitrageFree}: a pointwise check of Durrleman's condition g(k) &gt;= 0 (necessary for no butterfly
 *       arbitrage) using analytic derivatives of w.</li>
 * </ul>
 */
public final class SsviApproximation {

    public static class SsviParams {
        public final double eta;    // eta > 0
        public final double gamma;  // gamma in (0, 0.5]
        public final double rho;    // rho in (-1, 1)

        public SsviParams(double eta, double gamma, double rho) {
            if (!(eta > 0)) throw new IllegalArgumentException("eta must be > 0");
            if (!(gamma > 0 && gamma <= 0.5)) throw new IllegalArgumentException("gamma must be in (0, 0.5]");
            if (!(Math.abs(rho) < 1.0)) throw new IllegalArgumentException("abs(rho) must be < 1");
            this.eta = eta;
            this.gamma = gamma;
            this.rho = rho;
        }

        /**
         * Sufficient conditions (Gatheral and Jacquier, Theorem 4.2) for the surface to be free of butterfly
         * arbitrage at every maturity: theta*phi(theta)*(1+|rho|) &lt; 4 and theta*phi(theta)^2*(1+|rho|) &lt;= 4 for
         * all theta &gt; 0. For this phi, theta*phi = eta*(theta/(1+theta))^(1-gamma) &lt; eta, so the first reduces
         * to eta*(1+|rho|) &lt;= 4; and theta*phi^2 = eta^2 theta^a/(1+theta)^(a+1) with a = 1 - 2 gamma peaks at
         * theta = a with value eta^2 a^a/(1+a)^(a+1), so the second reduces to
         * eta^2 (1+|rho|) a^a/(1+a)^(a+1) &lt;= 4. Failing these does not prove arbitrage exists, but passing them
         * proves it does not.
         */
        public boolean satisfiesStaticNoArbitrageConditions() {
            double a = 1.0 - 2.0 * gamma;
            double peak = a == 0.0 ? 1.0 : Math.pow(a, a) / Math.pow(1.0 + a, a + 1.0);
            boolean first = eta * (1.0 + Math.abs(rho)) <= 4.0;
            boolean second = eta * eta * (1.0 + Math.abs(rho)) * peak <= 4.0;
            return first && second;
        }
    }

    private SsviApproximation() {}

    /**
     * Evaluates phi(theta) for SSVI. As theta approaches 0, phi grows without bound; the denominator is floored
     * at 1e-8 only to avoid dividing by zero.
     */
    public static double phi(double theta, double eta, double gamma) {
        if (theta <= 0) return 0.0;
        double denom = Math.pow(theta, gamma) * Math.pow(1.0 + theta, 1.0 - gamma);
        return eta / Math.max(1e-8, denom);
    }

    /**
     * Computes total variance w(k, theta) given log-moneyness k and ATM total variance theta.
     */
    public static double totalVariance(double k, double theta, SsviParams params) {
        if (theta <= 0) return 0.0;
        double phiVal = phi(theta, params.eta, params.gamma);
        double phik = phiVal * k;
        double rad = Math.sqrt((phik + params.rho) * (phik + params.rho) + (1.0 - params.rho * params.rho));
        return 0.5 * theta * (1.0 + params.rho * phik + rad);
    }

    /**
     * Implied volatility from the SSVI surface, with log-moneyness measured against the forward
     * {@code forward = S * exp((r - q) T)}. At {@code strike == forward} this returns {@code atmVol} exactly.
     */
    public static double impliedVolFromForward(double forward, double strike, double expiry, double atmVol, SsviParams params) {
        if (!Double.isFinite(forward) || forward <= 0) throw new IllegalArgumentException("forward must be finite and positive");
        if (!Double.isFinite(strike) || strike <= 0) throw new IllegalArgumentException("strike must be finite and positive");
        if (!Double.isFinite(expiry) || expiry <= 0) throw new IllegalArgumentException("expiry must be finite and positive");
        if (!Double.isFinite(atmVol) || atmVol <= 0) throw new IllegalArgumentException("atmVol must be finite and positive");
        double k = Math.log(strike / forward);
        double theta = atmVol * atmVol * expiry;
        return Math.sqrt(totalVariance(k, theta, params) / expiry);
    }

    /**
     * Implied volatility with log-moneyness against the <em>spot</em>, i.e. assuming the forward equals the spot
     * (r = q). When rates and dividends differ, use {@link #impliedVolFromForward}.
     */
    public static double impliedVol(double spot, double strike, double expiry, double atmVol, SsviParams params) {
        return impliedVolFromForward(spot, strike, expiry, atmVol, params);
    }

    /**
     * Durrleman's condition for the absence of butterfly arbitrage at log-moneyness k:
     * g(k) = (1 - k w'/(2w))^2 - (w'^2/4)(1/w + 1/4) + w''/2 &gt;= 0, using the analytic derivatives
     * w' = (theta/2) phi (rho + (phi k + rho)/R) and w'' = (theta/2) phi^2 (1 - rho^2) / R^3 with
     * R = sqrt((phi k + rho)^2 + 1 - rho^2). This is a pointwise check (necessary, not sufficient, across k and theta).
     */
    public static boolean isArbitrageFree(double k, double theta, SsviParams params) {
        double w = totalVariance(k, theta, params);
        if (!(w > 0)) {
            return false;
        }
        double phiVal = phi(theta, params.eta, params.gamma);
        double rho = params.rho;
        double radical = Math.sqrt((phiVal * k + rho) * (phiVal * k + rho) + 1.0 - rho * rho);
        double wPrime = 0.5 * theta * phiVal * (rho + (phiVal * k + rho) / radical);
        double wSecond = 0.5 * theta * phiVal * phiVal * (1.0 - rho * rho) / (radical * radical * radical);

        double term1 = 1.0 - (k * wPrime) / (2.0 * w);
        double g = term1 * term1 - (wPrime * wPrime / 4.0) * (1.0 / w + 0.25) + 0.5 * wSecond;
        return g >= 0.0;
    }
}
