package com.sbk.optionspricer;

/**
 * Closed-form Black-Scholes-Merton pricer (supports a continuous dividend
 * yield) for European options, plus the standard Greeks.
 *
 * Numerical stability: the standard d1/d2 formulas divide by
 * (volatility * sqrt(timeToExpiry)), which is undefined as either term
 * approaches zero. Rather than letting that produce NaN/Infinity silently,
 * both {@link #price} and {@link #greeks} explicitly detect this
 * near-expiry / zero-volatility case and fall back to the correct limiting
 * behavior directly: at expiry (or with zero volatility), an option is
 * worth exactly its intrinsic value, and delta becomes a step function
 * (0 or 1) rather than an undefined ratio.
 */
public class BlackScholesPricer {

    private static final double NEAR_ZERO = 1e-10;

    public static double price(OptionType type, OptionParameters p) {
        if (isDegenerate(p)) {
            return intrinsicValue(type, p);
        }

        double d1 = d1(p);
        double d2 = d2(p, d1);
        double discountedSpot = p.spot() * Math.exp(-p.dividendYield() * p.timeToExpiry());
        double discountedStrike = p.strike() * Math.exp(-p.riskFreeRate() * p.timeToExpiry());

        if (type == OptionType.CALL) {
            return discountedSpot * NormalDistribution.cdf(d1) - discountedStrike * NormalDistribution.cdf(d2);
        } else {
            return discountedStrike * NormalDistribution.cdf(-d2) - discountedSpot * NormalDistribution.cdf(-d1);
        }
    }

    public static Greeks greeks(OptionType type, OptionParameters p) {
        if (isDegenerate(p)) {
            // At/after expiry (or zero vol): delta is a step function, everything else is zero.
            double intrinsicDelta = switch (type) {
                case CALL -> p.spot() > p.strike() ? 1.0 : 0.0;
                case PUT -> p.spot() < p.strike() ? -1.0 : 0.0;
            };
            return new Greeks(intrinsicDelta, 0.0, 0.0, 0.0, 0.0);
        }

        double d1 = d1(p);
        double d2 = d2(p, d1);
        double sqrtT = Math.sqrt(p.timeToExpiry());
        double discFactorQ = Math.exp(-p.dividendYield() * p.timeToExpiry());
        double discFactorR = Math.exp(-p.riskFreeRate() * p.timeToExpiry());
        double pdfD1 = NormalDistribution.pdf(d1);

        double delta = (type == OptionType.CALL)
                ? discFactorQ * NormalDistribution.cdf(d1)
                : -discFactorQ * NormalDistribution.cdf(-d1);

        double gamma = discFactorQ * pdfD1 / (p.spot() * p.volatility() * sqrtT);

        double vega = p.spot() * discFactorQ * pdfD1 * sqrtT;

        double theta;
        double term1 = -(p.spot() * discFactorQ * pdfD1 * p.volatility()) / (2 * sqrtT);
        if (type == OptionType.CALL) {
            theta = term1
                    - p.riskFreeRate() * p.strike() * discFactorR * NormalDistribution.cdf(d2)
                    + p.dividendYield() * p.spot() * discFactorQ * NormalDistribution.cdf(d1);
        } else {
            theta = term1
                    + p.riskFreeRate() * p.strike() * discFactorR * NormalDistribution.cdf(-d2)
                    - p.dividendYield() * p.spot() * discFactorQ * NormalDistribution.cdf(-d1);
        }

        double rho = (type == OptionType.CALL)
                ? p.strike() * p.timeToExpiry() * discFactorR * NormalDistribution.cdf(d2)
                : -p.strike() * p.timeToExpiry() * discFactorR * NormalDistribution.cdf(-d2);

        return new Greeks(delta, gamma, vega, theta, rho);
    }

    private static boolean isDegenerate(OptionParameters p) {
        return p.timeToExpiry() <= NEAR_ZERO || p.volatility() <= NEAR_ZERO;
    }

    private static double intrinsicValue(OptionType type, OptionParameters p) {
        return (type == OptionType.CALL)
                ? Math.max(p.spot() - p.strike(), 0.0)
                : Math.max(p.strike() - p.spot(), 0.0);
    }

    private static double d1(OptionParameters p) {
        double numerator = Math.log(p.spot() / p.strike())
                + (p.riskFreeRate() - p.dividendYield() + 0.5 * p.volatility() * p.volatility()) * p.timeToExpiry();
        double denominator = p.volatility() * Math.sqrt(p.timeToExpiry());
        return numerator / denominator;
    }

    private static double d2(OptionParameters p, double d1) {
        return d1 - p.volatility() * Math.sqrt(p.timeToExpiry());
    }
}
