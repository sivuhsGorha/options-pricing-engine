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
        return price(type, p.spot(), p.strike(), p.timeToExpiry(), p.riskFreeRate(), p.volatility(), p.dividendYield());
    }

    public static double price(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double volatility, double dividendYield) {
        if (isDegenerate(timeToExpiry, volatility)) {
            return intrinsicValue(type, spot, strike);
        }

        double d1 = d1(spot, strike, timeToExpiry, riskFreeRate, volatility, dividendYield);
        double d2 = d2(d1, volatility, timeToExpiry);
        double discountedSpot = spot * Math.exp(-dividendYield * timeToExpiry);
        double discountedStrike = strike * Math.exp(-riskFreeRate * timeToExpiry);

        if (type == OptionType.CALL) {
            return discountedSpot * NormalDistribution.cdf(d1) - discountedStrike * NormalDistribution.cdf(d2);
        } else {
            return discountedStrike * NormalDistribution.cdf(-d2) - discountedSpot * NormalDistribution.cdf(-d1);
        }
    }

    public static Greeks greeks(OptionType type, OptionParameters p) {
        double[] out = new double[5];
        greeks(type, p.spot(), p.strike(), p.timeToExpiry(), p.riskFreeRate(), p.volatility(), p.dividendYield(), out);
        return new Greeks(out[0], out[1], out[2], out[3], out[4]);
    }

    public static void greeks(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double volatility, double dividendYield, double[] out) {
        if (isDegenerate(timeToExpiry, volatility)) {
            double intrinsicDelta = switch (type) {
                case CALL -> spot > strike ? 1.0 : 0.0;
                case PUT -> spot < strike ? -1.0 : 0.0;
            };
            out[0] = intrinsicDelta;
            out[1] = 0.0;
            out[2] = 0.0;
            out[3] = 0.0;
            out[4] = 0.0;
            return;
        }

        double d1 = d1(spot, strike, timeToExpiry, riskFreeRate, volatility, dividendYield);
        double d2 = d2(d1, volatility, timeToExpiry);
        double sqrtT = Math.sqrt(timeToExpiry);
        double discFactorQ = Math.exp(-dividendYield * timeToExpiry);
        double discFactorR = Math.exp(-riskFreeRate * timeToExpiry);
        double pdfD1 = NormalDistribution.pdf(d1);

        out[0] = (type == OptionType.CALL) ? discFactorQ * NormalDistribution.cdf(d1) : -discFactorQ * NormalDistribution.cdf(-d1);
        out[1] = discFactorQ * pdfD1 / (spot * volatility * sqrtT);
        out[2] = spot * discFactorQ * pdfD1 * sqrtT;

        double term1 = -(spot * discFactorQ * pdfD1 * volatility) / (2 * sqrtT);
        if (type == OptionType.CALL) {
            out[3] = term1 - riskFreeRate * strike * discFactorR * NormalDistribution.cdf(d2) + dividendYield * spot * discFactorQ * NormalDistribution.cdf(d1);
            out[4] = strike * timeToExpiry * discFactorR * NormalDistribution.cdf(d2);
        } else {
            out[3] = term1 + riskFreeRate * strike * discFactorR * NormalDistribution.cdf(-d2) - dividendYield * spot * discFactorQ * NormalDistribution.cdf(-d1);
            out[4] = -strike * timeToExpiry * discFactorR * NormalDistribution.cdf(-d2);
        }
    }

    private static boolean isDegenerate(double timeToExpiry, double volatility) {
        return timeToExpiry <= NEAR_ZERO || volatility <= NEAR_ZERO;
    }

    private static double intrinsicValue(OptionType type, double spot, double strike) {
        return (type == OptionType.CALL)
                ? Math.max(spot - strike, 0.0)
                : Math.max(strike - spot, 0.0);
    }

    private static double d1(double spot, double strike, double timeToExpiry, double riskFreeRate, double volatility, double dividendYield) {
        double numerator = Math.log(spot / strike)
                + (riskFreeRate - dividendYield + 0.5 * volatility * volatility) * timeToExpiry;
        double denominator = volatility * Math.sqrt(timeToExpiry);
        return numerator / denominator;
    }

    private static double d2(double d1, double volatility, double timeToExpiry) {
        return d1 - volatility * Math.sqrt(timeToExpiry);
    }
}
