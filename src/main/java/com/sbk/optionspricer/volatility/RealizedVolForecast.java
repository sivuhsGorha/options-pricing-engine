package com.sbk.optionspricer.volatility;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Realized-volatility forecasts (EWMA, and a HAR-style weighted average of daily, weekly and monthly realized vol)
 * as a baseline that does not come from the option chain.
 *
 * <p>What it is and is not. The price history must be one close per day: every return is read as a daily return and
 * annualised by 252. The HAR weights below are illustrative and have not been fitted to any data, and the realized
 * measures are close-to-close (the "daily" term is a single squared return, which is noisy), so this is HAR-shaped,
 * not Corsi's estimator. The term-structure step pulls the forecast toward a fixed {@link #LONG_TERM_UNCONDITIONAL_VOL}
 * prior. Implied volatility sits above realized volatility on average (the variance risk premium), so the gap
 * IV - forecast is positive most days; it is evidence to record, and a poor reference to trade against by itself.
 */
public final class RealizedVolForecast implements FairVolProvider {

    public static final double DEFAULT_EWMA_LAMBDA = 0.94; // RiskMetrics daily decay
    public static final double TRADING_DAYS_PER_YEAR = 252.0;
    public static final double LONG_TERM_UNCONDITIONAL_VOL = 0.18; // 18% SPY baseline
    public static final double MEAN_REVERSION_SPEED = 2.0;

    /** Fewest daily returns any forecast is offered on. */
    public static final int MIN_EWMA_RETURNS = 10;
    /** Daily returns the HAR monthly window needs. */
    public static final int HAR_WINDOW = 22;

    // Illustrative HAR weights (the shape follows Corsi 2009; the numbers are not fitted to data)
    public static final double HAR_BETA_0 = 0.015;
    public static final double HAR_BETA_DAILY = 0.38;
    public static final double HAR_BETA_WEEKLY = 0.33;
    public static final double HAR_BETA_MONTHLY = 0.22;

    /**
     * Calibrated or baseline coefficients for Corsi (2009) HAR-RV linear model:
     * RV_{t+1} = beta_0 + beta_d * RV_d + beta_w * RV_w + beta_m * RV_m
     */
    public record HarCoefficients(double beta0, double betaDaily, double betaWeekly, double betaMonthly, double rSquared) {
        public static final HarCoefficients DEFAULT = new HarCoefficients(HAR_BETA_0, HAR_BETA_DAILY, HAR_BETA_WEEKLY, HAR_BETA_MONTHLY, Double.NaN);

        public HarCoefficients {
            if (!Double.isFinite(beta0) || !Double.isFinite(betaDaily) || !Double.isFinite(betaWeekly) || !Double.isFinite(betaMonthly)) {
                throw new IllegalArgumentException("HAR beta coefficients must be finite");
            }
        }
    }

    public enum Model {
        EWMA,
        HAR,
        HYBRID
    }

    private final Model model;
    private final double lambda;
    private final List<Double> priceHistory = new ArrayList<>();
    private final int maxHistory;
    private final HarCoefficients harCoefficients;

    public RealizedVolForecast() {
        this(Model.HAR, DEFAULT_EWMA_LAMBDA, 500, HarCoefficients.DEFAULT);
    }

    public RealizedVolForecast(Model model) {
        this(model, DEFAULT_EWMA_LAMBDA, 500, HarCoefficients.DEFAULT);
    }

    public RealizedVolForecast(Model model, double lambda, int maxHistory) {
        this(model, lambda, maxHistory, HarCoefficients.DEFAULT);
    }

    public RealizedVolForecast(Model model, double lambda, int maxHistory, HarCoefficients harCoefficients) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (lambda <= 0.0 || lambda >= 1.0) {
            throw new IllegalArgumentException("lambda must be in (0, 1)");
        }
        if (maxHistory < 10) {
            throw new IllegalArgumentException("maxHistory must be at least 10");
        }
        if (harCoefficients == null) {
            throw new IllegalArgumentException("harCoefficients must not be null");
        }
        this.model = model;
        this.lambda = lambda;
        this.maxHistory = maxHistory;
        this.harCoefficients = harCoefficients;
    }

    public HarCoefficients harCoefficients() {
        return harCoefficients;
    }

    public synchronized void observeSpot(double spot) {
        if (!Double.isFinite(spot) || spot <= 0.0) {
            return;
        }
        priceHistory.add(spot);
        if (priceHistory.size() > maxHistory) {
            priceHistory.remove(0);
        }
    }

    public synchronized void setPrices(double[] prices) {
        priceHistory.clear();
        if (prices != null) {
            for (double p : prices) {
                if (Double.isFinite(p) && p > 0.0) {
                    priceHistory.add(p);
                }
            }
        }
    }

    public synchronized int historySize() {
        return priceHistory.size();
    }

    /**
     * Estimates annualized EWMA volatility from log returns.
     */
    public static double ewmaVol(double[] logReturns, double lambda, double periodsPerYear) {
        if (logReturns == null || logReturns.length == 0) {
            throw new IllegalArgumentException("logReturns must not be empty");
        }
        if (lambda <= 0.0 || lambda >= 1.0) {
            throw new IllegalArgumentException("lambda must be in (0, 1)");
        }
        double var = logReturns[0] * logReturns[0];
        for (int i = 1; i < logReturns.length; i++) {
            double r = logReturns[i];
            var = lambda * var + (1.0 - lambda) * (r * r);
        }
        return Math.sqrt(var * periodsPerYear);
    }

    /**
     * Corsi HAR-RV 1-step ahead forecast:
     * RV_{t+1} = beta_0 + beta_d * RV_d + beta_w * RV_w + beta_m * RV_m
     */
    public static double harForecast(double[] dailyLogReturns) {
        return harForecast(dailyLogReturns, HarCoefficients.DEFAULT);
    }

    /**
     * Corsi HAR-RV 1-step ahead forecast with custom/calibrated coefficients.
     */
    public static double harForecast(double[] dailyLogReturns, HarCoefficients coeffs) {
        if (dailyLogReturns == null || dailyLogReturns.length < 22) {
            throw new IllegalArgumentException("HAR-RV requires at least 22 daily returns (1 month)");
        }
        HarCoefficients c = coeffs == null ? HarCoefficients.DEFAULT : coeffs;
        int n = dailyLogReturns.length;

        // Daily RV
        double rDaily = dailyLogReturns[n - 1];
        double rvDaily = Math.sqrt(rDaily * rDaily * TRADING_DAYS_PER_YEAR);

        // Weekly RV (5 days)
        double sumSqWeekly = 0.0;
        for (int i = n - 5; i < n; i++) {
            sumSqWeekly += dailyLogReturns[i] * dailyLogReturns[i];
        }
        double rvWeekly = Math.sqrt((sumSqWeekly / 5.0) * TRADING_DAYS_PER_YEAR);

        // Monthly RV (22 days)
        double sumSqMonthly = 0.0;
        for (int i = n - 22; i < n; i++) {
            sumSqMonthly += dailyLogReturns[i] * dailyLogReturns[i];
        }
        double rvMonthly = Math.sqrt((sumSqMonthly / 22.0) * TRADING_DAYS_PER_YEAR);

        double forecast = c.beta0() + (c.betaDaily() * rvDaily) + (c.betaWeekly() * rvWeekly) + (c.betaMonthly() * rvMonthly);
        return Math.max(0.01, Math.min(forecast, 2.0));
    }

    /**
     * Fits Corsi (2009) HAR-RV model parameters via Ordinary Least Squares (OLS) regression on historical daily log returns:
     * RV_{t+1} = beta_0 + beta_d * RV_d + beta_w * RV_w + beta_m * RV_m + epsilon
     *
     * @param dailyLogReturns chronological daily log returns (at least 44 days needed for 22-day predictor window + 22 observations)
     * @return fitted HarCoefficients including empirical R^2, or HarCoefficients.DEFAULT if sample size is insufficient or singular
     */
    public static HarCoefficients calibrateHar(double[] dailyLogReturns) {
        if (dailyLogReturns == null || dailyLogReturns.length < 44) {
            return HarCoefficients.DEFAULT;
        }
        int n = dailyLogReturns.length;
        double[][] a = new double[4][4];
        double[] b = new double[4];
        double sumY = 0.0;
        double sumY2 = 0.0;
        int count = 0;

        for (int t = 21; t < n - 1; t++) {
            double rNext = dailyLogReturns[t + 1];
            double y = Math.abs(rNext) * Math.sqrt(TRADING_DAYS_PER_YEAR);

            double x0 = 1.0;
            double rDaily = dailyLogReturns[t];
            double x1 = Math.abs(rDaily) * Math.sqrt(TRADING_DAYS_PER_YEAR);

            double sumSqWeekly = 0.0;
            for (int k = t - 4; k <= t; k++) {
                sumSqWeekly += dailyLogReturns[k] * dailyLogReturns[k];
            }
            double x2 = Math.sqrt((sumSqWeekly / 5.0) * TRADING_DAYS_PER_YEAR);

            double sumSqMonthly = 0.0;
            for (int k = t - 21; k <= t; k++) {
                sumSqMonthly += dailyLogReturns[k] * dailyLogReturns[k];
            }
            double x3 = Math.sqrt((sumSqMonthly / 22.0) * TRADING_DAYS_PER_YEAR);

            double[] x = new double[]{x0, x1, x2, x3};
            for (int i = 0; i < 4; i++) {
                b[i] += x[i] * y;
                for (int j = 0; j < 4; j++) {
                    a[i][j] += x[i] * x[j];
                }
            }
            sumY += y;
            sumY2 += y * y;
            count++;
        }

        if (count < 22) {
            return HarCoefficients.DEFAULT;
        }

        double[][] aug = new double[4][5];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(a[i], 0, aug[i], 0, 4);
            aug[i][4] = b[i];
        }

        for (int i = 0; i < 4; i++) {
            int maxRow = i;
            double maxVal = Math.abs(aug[i][i]);
            for (int k = i + 1; k < 4; k++) {
                if (Math.abs(aug[k][i]) > maxVal) {
                    maxVal = Math.abs(aug[k][i]);
                    maxRow = k;
                }
            }
            if (maxVal < 1e-12) {
                return HarCoefficients.DEFAULT;
            }
            double[] tmp = aug[i];
            aug[i] = aug[maxRow];
            aug[maxRow] = tmp;

            for (int k = i + 1; k < 4; k++) {
                double factor = aug[k][i] / aug[i][i];
                for (int j = i; j <= 4; j++) {
                    aug[k][j] -= factor * aug[i][j];
                }
            }
        }

        double[] beta = new double[4];
        for (int i = 3; i >= 0; i--) {
            double sum = aug[i][4];
            for (int j = i + 1; j < 4; j++) {
                sum -= aug[i][j] * beta[j];
            }
            beta[i] = sum / aug[i][i];
        }

        for (int i = 0; i < 4; i++) {
            if (!Double.isFinite(beta[i]) || Math.abs(beta[i]) > 10.0) {
                return HarCoefficients.DEFAULT;
            }
        }

        double ssRes = sumY2;
        for (int i = 0; i < 4; i++) {
            ssRes -= beta[i] * b[i];
        }
        ssRes = Math.max(0.0, ssRes);
        double ssTot = Math.max(1e-12, sumY2 - (sumY * sumY / count));
        double rSquared = Math.max(0.0, Math.min(1.0, 1.0 - (ssRes / ssTot)));

        return new HarCoefficients(beta[0], beta[1], beta[2], beta[3], rSquared);
    }

    public static HarCoefficients calibrateHarFromPrices(double[] prices) {
        if (prices == null || prices.length < 45) {
            return HarCoefficients.DEFAULT;
        }
        double[] logReturns = new double[prices.length - 1];
        for (int i = 1; i < prices.length; i++) {
            if (prices[i] <= 0.0 || prices[i - 1] <= 0.0) {
                return HarCoefficients.DEFAULT;
            }
            logReturns[i - 1] = Math.log(prices[i] / prices[i - 1]);
        }
        return calibrateHar(logReturns);
    }

    /**
     * Term structure adjustment for options expiring in T years using Ornstein-Uhlenbeck mean-reverting variance.
     */
    public static double termStructureAdjust(double shortTermVol, double timeToExpiry) {
        if (!(timeToExpiry > 0.0)) {
            return shortTermVol;
        }
        double var0 = shortTermVol * shortTermVol;
        double varInf = LONG_TERM_UNCONDITIONAL_VOL * LONG_TERM_UNCONDITIONAL_VOL;
        double factor = (1.0 - Math.exp(-MEAN_REVERSION_SPEED * timeToExpiry)) / (MEAN_REVERSION_SPEED * timeToExpiry);
        double termVar = varInf + (var0 - varInf) * factor;
        return Math.sqrt(Math.max(1e-4, termVar));
    }

    /**
     * Computes the Variance Risk Premium: VRP = IV - RV_forecast.
     * Positive VRP indicates implied volatility is expensive relative to expected realization.
     */
    public static double varianceRiskPremium(double impliedVol, double realizedForecast) {
        return impliedVol - realizedForecast;
    }

    @Override
    public synchronized OptionalDouble fairVol(String symbol, LocalDate expiry, double strike, double timeToExpiry) {
        int returnCount = priceHistory.size() - 1;
        // Too little history is no answer, not a different model's answer: a "HAR" figure that is really an EWMA of a
        // few days would be read as the one the name says.
        boolean needsHarWindow = model == Model.HAR || model == Model.HYBRID;
        if (returnCount < MIN_EWMA_RETURNS || (needsHarWindow && returnCount < HAR_WINDOW)) {
            return OptionalDouble.empty();
        }
        double[] returns = new double[priceHistory.size() - 1];
        for (int i = 1; i < priceHistory.size(); i++) {
            returns[i - 1] = Math.log(priceHistory.get(i) / priceHistory.get(i - 1));
        }

        double baseForecast;
        if (model == Model.HAR) {
            baseForecast = harForecast(returns, harCoefficients);
        } else if (model == Model.HYBRID) {
            double har = harForecast(returns, harCoefficients);
            double ewma = ewmaVol(returns, lambda, TRADING_DAYS_PER_YEAR);
            baseForecast = 0.6 * har + 0.4 * ewma;
        } else {
            baseForecast = ewmaVol(returns, lambda, TRADING_DAYS_PER_YEAR);
        }

        double termVol = termStructureAdjust(baseForecast, Math.max(timeToExpiry, 1.0 / 252.0));
        return OptionalDouble.of(termVol);
    }
}
