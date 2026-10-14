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

    public enum Model {
        EWMA,
        HAR,
        HYBRID
    }

    private final Model model;
    private final double lambda;
    private final List<Double> priceHistory = new ArrayList<>();
    private final int maxHistory;

    public RealizedVolForecast() {
        this(Model.HAR, DEFAULT_EWMA_LAMBDA, 500);
    }

    public RealizedVolForecast(Model model) {
        this(model, DEFAULT_EWMA_LAMBDA, 500);
    }

    public RealizedVolForecast(Model model, double lambda, int maxHistory) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (lambda <= 0.0 || lambda >= 1.0) {
            throw new IllegalArgumentException("lambda must be in (0, 1)");
        }
        if (maxHistory < 10) {
            throw new IllegalArgumentException("maxHistory must be at least 10");
        }
        this.model = model;
        this.lambda = lambda;
        this.maxHistory = maxHistory;
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
        if (dailyLogReturns == null || dailyLogReturns.length < 22) {
            throw new IllegalArgumentException("HAR-RV requires at least 22 daily returns (1 month)");
        }
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

        double forecast = HAR_BETA_0 + (HAR_BETA_DAILY * rvDaily) + (HAR_BETA_WEEKLY * rvWeekly) + (HAR_BETA_MONTHLY * rvMonthly);
        return Math.max(0.01, Math.min(forecast, 2.0));
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
            baseForecast = harForecast(returns);
        } else if (model == Model.HYBRID) {
            double har = harForecast(returns);
            double ewma = ewmaVol(returns, lambda, TRADING_DAYS_PER_YEAR);
            baseForecast = 0.6 * har + 0.4 * ewma;
        } else {
            baseForecast = ewmaVol(returns, lambda, TRADING_DAYS_PER_YEAR);
        }

        double termVol = termStructureAdjust(baseForecast, Math.max(timeToExpiry, 1.0 / 252.0));
        return OptionalDouble.of(termVol);
    }
}
