package com.sbk.optionspricer.volatility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Walk-forward comparison of realized-volatility forecasts on one series of daily closes. At each origin only the
 * returns up to that day are used (the HAR fit is redone on exactly those), and the forecast is scored against the
 * volatility realised over the next {@code horizon} days. It says how well each model predicted past realized
 * volatility on this series; it says nothing about options prices or trading profit.
 *
 * <p>Scores: RMSE and bias in annualised volatility (forecast minus realised, so a negative bias is a forecast that ran
 * low), and QLIKE on variance, the loss that punishes under-forecasting more than over-forecasting. Origins are
 * consecutive days, so their horizon windows overlap and the scores are not independent observations: a small
 * difference between two models is within the noise.
 */
public final class ForecastEvaluator {

    public static final String TRAILING = "trailing 22d";
    public static final String EWMA = "EWMA";
    public static final String HAR_DEFAULT = "HAR default";
    public static final String HAR_FITTED = "HAR fitted";

    /** @param n evaluations scored; vols are annualised fractions (0.15 = 15%) */
    public record Score(String model, int n, double rmse, double bias, double qlike) {}

    /** @param fittedShare share of origins at which the HAR fit ran (the rest fell back to the default weights) */
    public record Result(int horizon, int evaluations, double meanRealized, List<Score> scores, double fittedShare) {}

    private static final double ANNUALISE = RealizedVolForecast.TRADING_DAYS_PER_YEAR;
    private static final int TRAILING_WINDOW = 22;

    private ForecastEvaluator() {}

    /**
     * @param closes      completed daily closes in date order
     * @param horizonDays days ahead whose realised volatility is the target
     * @param minTrain    returns needed before the first origin (at least 44, or the HAR fit falls back to its defaults)
     */
    public static Result evaluate(double[] closes, int horizonDays, int minTrain) {
        if (closes == null || horizonDays < 1 || minTrain < TRAILING_WINDOW) {
            throw new IllegalArgumentException("closes are required, horizonDays >= 1 and minTrain >= " + TRAILING_WINDOW);
        }
        double[] returns = new double[closes.length - 1];
        for (int i = 1; i < closes.length; i++) {
            if (!(closes[i] > 0.0) || !(closes[i - 1] > 0.0)) {
                throw new IllegalArgumentException("closes must be positive");
            }
            returns[i - 1] = Math.log(closes[i] / closes[i - 1]);
        }
        int origins = returns.length - horizonDays - minTrain + 1;
        if (origins < 1) {
            throw new IllegalArgumentException("need at least " + (minTrain + horizonDays + 1) + " closes for a " + horizonDays
                    + "-day horizon, got " + closes.length);
        }

        String[] names = {TRAILING, EWMA, HAR_DEFAULT, HAR_FITTED};
        double[][] errors = new double[4][origins];
        double[][] qlike = new double[4][origins];
        double sumRealized = 0.0;
        int fitted = 0;
        int used = 0;
        for (int o = 0; o < origins; o++) {
            int last = minTrain - 1 + o; // index of the last return known at this origin
            double[] known = Arrays.copyOfRange(returns, 0, last + 1);
            double sumSq = 0.0;
            for (int k = last + 1; k <= last + horizonDays; k++) sumSq += returns[k] * returns[k];
            double realized = Math.sqrt(sumSq / horizonDays * ANNUALISE);
            if (!(realized * realized > 1e-12)) {
                continue; // a window with no movement at all has no variance to score against
            }
            RealizedVolForecast.HarCoefficients fit = RealizedVolForecast.calibrateHar(known);
            if (Double.isFinite(fit.rSquared())) fitted++;
            double[] forecasts = {
                    trailing(known),
                    RealizedVolForecast.ewmaVol(known, RealizedVolForecast.DEFAULT_EWMA_LAMBDA, ANNUALISE),
                    RealizedVolForecast.harForecast(known),
                    RealizedVolForecast.harForecast(known, fit)};
            for (int m = 0; m < 4; m++) {
                errors[m][used] = forecasts[m] - realized;
                double ratio = (realized * realized) / (forecasts[m] * forecasts[m]);
                qlike[m][used] = ratio - Math.log(ratio) - 1.0;
            }
            sumRealized += realized;
            used++;
        }
        if (used == 0) {
            throw new IllegalArgumentException("the series has no movement to forecast");
        }
        List<Score> scores = new ArrayList<>();
        for (int m = 0; m < 4; m++) {
            double se = 0.0, bias = 0.0, ql = 0.0;
            for (int i = 0; i < used; i++) {
                se += errors[m][i] * errors[m][i];
                bias += errors[m][i];
                ql += qlike[m][i];
            }
            scores.add(new Score(names[m], used, Math.sqrt(se / used), bias / used, ql / used));
        }
        return new Result(horizonDays, used, sumRealized / used, List.copyOf(scores), fitted / (double) used);
    }

    private static double trailing(double[] known) {
        double sumSq = 0.0;
        for (int k = known.length - TRAILING_WINDOW; k < known.length; k++) sumSq += known[k] * known[k];
        return Math.sqrt(sumSq / TRAILING_WINDOW * ANNUALISE);
    }
}
