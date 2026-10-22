package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.volatility.VrpStudy.Close;

import java.util.ArrayList;
import java.util.List;

/**
 * Volatility targeting on a daily index against holding it: each day the exposure is {@code min(1, target / forecast)}, where the forecast is the EWMA
 * volatility of the returns up to the previous close (the same RiskMetrics decay the engine uses for its realized-volatility readout). A calm market is held in
 * full, a stormy one in part, and the rest sits in cash, which earns nothing here. Nothing is borrowed. The exposure for a day is set before that day's return
 * is known, so a shock is taken at the exposure the calm before it allowed. Every change of exposure costs {@code costBps} basis points of the amount traded.
 *
 * <p>This is a risk control, not a forecast of direction: it does not say where the market is going, only that the next weeks are likely to be rougher or
 * calmer than average, which is the one thing about a market that does persist. Because holding less cuts the fall from a peak by construction, a smaller
 * fall proves nothing here; the rule is about return per unit of risk. It passes only if, with the target chosen in advance (10% a year):
 * <ol>
 *   <li>the largest fall from a peak is at most 80% of the market's;</li>
 *   <li>the Sharpe ratio (no risk-free rate for either side, so scaling the exposure does not move it) is at least {@value #SHARPE_MARGIN} above the market's;</li>
 *   <li>the Sharpe ratio is at least the market's in each half of the data;</li>
 *   <li>it is also at least the market's with targets of 8% and 12% and with the slower 0.97 decay. They are a check, never a choice.</li>
 * </ol>
 * Meeting the first two but not the last two is "UNPROVEN", and missing either of the first two is "FAIL".
 *
 * <p>The margin was calibrated, before the real index was looked at, on simulated markets that have clustering volatility and no drift, where avoiding the
 * storms earns nothing. With no margin the rule passed 20% of 400 such markets; a margin of 0.10 let 11% through, 0.15 let 5%, 0.20 let 2%. The price of 0.15 is power:
 * the benefit this kind of rule is known for in the literature is of that order, so a real but modest gain will often miss it and come out UNPROVEN or FAIL.
 * The false-positive test fixes how often the rule passes on markets with nothing to find. Price returns, no dividends.
 */
public final class VolTargetStudy {

    static final double TRADING_DAYS = 252.0;
    static final int WARM_UP = 60;
    /** How much better than holding the Sharpe ratio must be: calibrated on simulated markets with nothing to find, see the class comment. */
    public static final double SHARPE_MARGIN = 0.15;
    static final double[] NEIGHBOUR_TARGETS = {0.08, 0.12};
    static final double NEIGHBOUR_LAMBDA = 0.97;

    public record Perf(double totalReturn, double cagr, double annualVol, double sharpe, double maxDrawdown, double timeInMarket, double turnover,
                       double worstDay, double years) {}

    public record Result(Perf target, Perf hold, double targetVol, int days) {}

    public record Verdict(boolean drawdownSmaller, boolean sharpeClearlyBetter, boolean bothHalvesAgree, boolean neighboursAgree,
                          double targetMaxDrawdown, double holdMaxDrawdown, double targetSharpe, double holdSharpe) {
        public String overall() {
            if (!drawdownSmaller || !sharpeClearlyBetter) {
                return "FAIL";
            }
            return bothHalvesAgree && neighboursAgree ? "PASS" : "UNPROVEN";
        }
    }

    private VolTargetStudy() {}

    public static Result run(List<Close> closes, double targetVol, double lambda, double costBps) {
        if (closes == null) {
            throw new IllegalArgumentException("closes are required");
        }
        if (!(targetVol > 0.0) || targetVol > 1.0) {
            throw new IllegalArgumentException("the target volatility must be between 0 and 100% a year");
        }
        if (!(lambda > 0.0 && lambda < 1.0)) {
            throw new IllegalArgumentException("the decay must be between 0 and 1");
        }
        if (costBps < 0.0) {
            throw new IllegalArgumentException("costs cannot be negative");
        }
        if (closes.size() < WARM_UP + 20) {
            throw new IllegalArgumentException("need at least " + (WARM_UP + 20) + " closes, got " + closes.size());
        }
        int n = closes.size();
        double[] returns = new double[n];
        for (int i = 1; i < n; i++) {
            returns[i] = closes.get(i).close() / closes.get(i - 1).close() - 1.0;
        }
        // EWMA variance through the close of each day, started from the plain variance of the first returns
        double seed = 0.0;
        for (int i = 1; i <= 20; i++) {
            seed += returns[i] * returns[i];
        }
        double variance = seed / 20.0;
        double[] varianceThrough = new double[n];
        for (int i = 1; i < n; i++) {
            variance = lambda * variance + (1.0 - lambda) * returns[i] * returns[i];
            varianceThrough[i] = variance;
        }

        int days = n - WARM_UP - 1;
        double[] targetDaily = new double[days];
        double[] holdDaily = new double[days];
        double cost = costBps / 10_000.0;
        double previousExposure = 0.0;
        double exposureSum = 0.0, turnover = 0.0;
        for (int t = WARM_UP + 1; t < n; t++) {
            double forecast = Math.sqrt(varianceThrough[t - 1] * TRADING_DAYS); // through the close of the day before
            double exposure = forecast > 0.0 ? Math.min(1.0, targetVol / forecast) : 1.0;
            double traded = Math.abs(exposure - previousExposure);
            targetDaily[t - WARM_UP - 1] = exposure * returns[t] - cost * traded;
            holdDaily[t - WARM_UP - 1] = returns[t];
            exposureSum += exposure;
            turnover += traded;
            previousExposure = exposure;
        }
        return new Result(perf(targetDaily, exposureSum / days, turnover), perf(holdDaily, 1.0, 0.0), targetVol, days);
    }

    private static Perf perf(double[] daily, double averageExposure, double turnover) {
        int n = daily.length;
        double equity = 1.0, peak = 1.0, maxDrawdown = 0.0, sum = 0.0, worst = 0.0;
        for (double r : daily) {
            equity *= 1.0 + r;
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, 1.0 - equity / peak);
            sum += r;
            worst = Math.min(worst, r);
        }
        double mean = sum / n;
        double variance = 0.0;
        for (double r : daily) {
            variance += (r - mean) * (r - mean);
        }
        double vol = Math.sqrt(variance / Math.max(1, n - 1)) * Math.sqrt(TRADING_DAYS);
        double years = n / TRADING_DAYS;
        return new Perf(equity - 1.0, Math.pow(equity, 1.0 / years) - 1.0, vol, vol > 0.0 ? mean * TRADING_DAYS / vol : 0.0, maxDrawdown, averageExposure,
                turnover, worst, years);
    }

    /** Applies the pass rule to the chosen target and checks the halves and the neighbouring settings. */
    public static Verdict evaluate(List<Close> closes, double targetVol, double lambda, double costBps) {
        Result full = run(closes, targetVol, lambda, costBps);
        boolean drawdown = full.target().maxDrawdown() <= 0.80 * full.hold().maxDrawdown();
        boolean sharpe = full.target().sharpe() >= full.hold().sharpe() + SHARPE_MARGIN;

        int mid = closes.size() / 2;
        boolean halves = notWorse(closes.subList(0, mid), targetVol, lambda, costBps) && notWorse(closes.subList(mid, closes.size()), targetVol, lambda, costBps);

        boolean neighbours = true;
        for (double t : NEIGHBOUR_TARGETS) {
            neighbours &= notWorse(closes, t, lambda, costBps);
        }
        neighbours &= notWorse(closes, targetVol, NEIGHBOUR_LAMBDA, costBps);
        return new Verdict(drawdown, sharpe, halves, neighbours, full.target().maxDrawdown(), full.hold().maxDrawdown(), full.target().sharpe(), full.hold().sharpe());
    }

    private static boolean notWorse(List<Close> part, double targetVol, double lambda, double costBps) {
        try {
            Result r = run(new ArrayList<>(part), targetVol, lambda, costBps);
            return r.target().sharpe() >= r.hold().sharpe();
        } catch (IllegalArgumentException tooShort) {
            return false;
        }
    }
}
