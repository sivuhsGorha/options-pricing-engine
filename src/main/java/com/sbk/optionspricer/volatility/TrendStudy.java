package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.volatility.VrpStudy.Close;

import java.util.ArrayList;
import java.util.List;

/**
 * Trend-following on a daily index against holding it: long when the close is above its simple moving average, in cash when it is not. The signal read at
 * the close of a day sets the position for the next day's return, so nothing is earned from a move the rule could not yet have seen. Every change of
 * position costs {@code costBps} basis points, and cash earns {@code cashRate} a year (zero by default, which understates the strategy: it is out of the
 * market part of the time and cash does pay). Returns are price returns, with no dividends for either side.
 *
 * <p>The rule that says whether it earns its keep is fixed here, before any run, and is about <em>risk</em>, not about beating the market's return, which a
 * rule that sits in cash part of the time will rarely do in a rising market. It passes only if, with the one window chosen in advance (200 days):
 * <ol>
 *   <li>the largest fall from a peak is at most three quarters of the market's;</li>
 *   <li>the Sharpe ratio (zero risk-free rate for both) is at least {@value #SHARPE_MARGIN} above the market's;</li>
 *   <li>the first half and the second half of the data each show a smaller fall than holding (so it is not one episode);</li>
 *   <li>the 100, 150 and 250-day windows also show the smaller fall (so it is not one lucky parameter). They are a check, never a choice.</li>
 * </ol>
 * Meeting the first two but not the last two is "UNPROVEN", and missing either of the first two is "FAIL". It is one market over about ten years.
 *
 * <p>The Sharpe margin was not picked by looking at the real index. A first version asked only for "not worse" and passed 27% of 60 simulated ten-year markets
 * with no drift and no trend, where there is nothing to find: cutting exposure lowers the fall from a peak by itself, and Sharpe is a coin toss. Run on 400
 * such markets, a margin of 0.4 let about 5% through, which is the false-positive rate the rule is meant to have; 0.3 let 10% through and 0.2 let 16%. The
 * price is power: a real but modest benefit will often miss a margin that large, and then the honest answer is UNPROVEN or FAIL, not a pass.
 */
public final class TrendStudy {

    static final double TRADING_DAYS = 252.0;
    static final int[] NEIGHBOUR_WINDOWS = {100, 150, 250};
    /** How much better than holding the Sharpe ratio must be: calibrated on simulated markets with nothing to find, see the class comment. */
    public static final double SHARPE_MARGIN = 0.4;

    public record Perf(double totalReturn, double cagr, double annualVol, double sharpe, double maxDrawdown, double timeInMarket, int switches, double years) {}

    public record Result(Perf trend, Perf hold, int window, int days) {}

    public record Verdict(boolean drawdownMuchSmaller, boolean sharpeClearlyBetter, boolean bothHalvesAgree, boolean neighbourWindowsAgree,
                          double trendMaxDrawdown, double holdMaxDrawdown, double trendSharpe, double holdSharpe) {
        public String overall() {
            if (!drawdownMuchSmaller || !sharpeClearlyBetter) {
                return "FAIL";
            }
            return bothHalvesAgree && neighbourWindowsAgree ? "PASS" : "UNPROVEN";
        }
    }

    private TrendStudy() {}

    public static Result run(List<Close> closes, int window, double costBps, double cashRate) {
        if (closes == null) {
            throw new IllegalArgumentException("closes are required");
        }
        if (window < 2) {
            throw new IllegalArgumentException("the window must be at least 2 days");
        }
        if (costBps < 0.0 || cashRate < 0.0) {
            throw new IllegalArgumentException("costs and the cash rate cannot be negative");
        }
        if (closes.size() < window + 20) {
            throw new IllegalArgumentException("need at least " + (window + 20) + " closes for a window of " + window + ", got " + closes.size());
        }
        int n = closes.size();
        double[] price = new double[n];
        for (int i = 0; i < n; i++) {
            price[i] = closes.get(i).close();
        }
        // running sum for the moving average at each close
        double[] average = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += price[i];
            if (i >= window) {
                sum -= price[i - window];
            }
            average[i] = i >= window - 1 ? sum / window : Double.NaN;
        }

        int first = window; // the first day with a signal from the day before
        int days = n - first;
        double[] holdDaily = new double[days];
        double[] trendDaily = new double[days];
        double cashDaily = Math.pow(1.0 + cashRate, 1.0 / TRADING_DAYS) - 1.0;
        double cost = costBps / 10_000.0;
        boolean invested = false;
        int switches = 0;
        int daysInvested = 0;
        for (int t = first; t < n; t++) {
            double r = price[t] / price[t - 1] - 1.0;
            boolean signal = price[t - 1] > average[t - 1]; // read at the close of the day before
            double day = signal ? r : cashDaily;
            if (signal != invested) {
                day -= cost;
                switches++;
                invested = signal;
            }
            if (signal) {
                daysInvested++;
            }
            holdDaily[t - first] = r;
            trendDaily[t - first] = day;
        }
        return new Result(perf(trendDaily, switches, daysInvested / (double) days), perf(holdDaily, 0, 1.0), window, days);
    }

    private static Perf perf(double[] daily, int switches, double timeInMarket) {
        int n = daily.length;
        double equity = 1.0, peak = 1.0, maxDrawdown = 0.0, sum = 0.0;
        for (double r : daily) {
            equity *= 1.0 + r;
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, 1.0 - equity / peak);
            sum += r;
        }
        double mean = sum / n;
        double variance = 0.0;
        for (double r : daily) {
            variance += (r - mean) * (r - mean);
        }
        double vol = Math.sqrt(variance / Math.max(1, n - 1)) * Math.sqrt(TRADING_DAYS);
        double years = n / TRADING_DAYS;
        double total = equity - 1.0;
        double cagr = Math.pow(equity, 1.0 / years) - 1.0;
        double sharpe = vol > 0.0 ? mean * TRADING_DAYS / vol : 0.0;
        return new Perf(total, cagr, vol, sharpe, maxDrawdown, timeInMarket, switches, years);
    }

    /** Applies the pass rule to the chosen window and checks the halves and the neighbouring windows. */
    public static Verdict evaluate(List<Close> closes, int window, double costBps, double cashRate) {
        Result full = run(closes, window, costBps, cashRate);
        boolean drawdown = full.trend().maxDrawdown() <= 0.75 * full.hold().maxDrawdown();
        boolean sharpe = full.trend().sharpe() >= full.hold().sharpe() + SHARPE_MARGIN;

        int mid = closes.size() / 2;
        boolean halves = smallerFall(closes.subList(0, mid), window, costBps, cashRate) && smallerFall(closes.subList(mid, closes.size()), window, costBps, cashRate);

        boolean neighbours = true;
        for (int w : NEIGHBOUR_WINDOWS) {
            if (w == window) {
                continue;
            }
            try {
                Result r = run(closes, w, costBps, cashRate);
                neighbours &= r.trend().maxDrawdown() < r.hold().maxDrawdown();
            } catch (IllegalArgumentException tooShort) {
                neighbours = false;
            }
        }
        return new Verdict(drawdown, sharpe, halves, neighbours, full.trend().maxDrawdown(), full.hold().maxDrawdown(), full.trend().sharpe(), full.hold().sharpe());
    }

    private static boolean smallerFall(List<Close> part, int window, double costBps, double cashRate) {
        try {
            Result r = run(new ArrayList<>(part), window, costBps, cashRate);
            return r.trend().maxDrawdown() < r.hold().maxDrawdown();
        } catch (IllegalArgumentException tooShort) {
            return false;
        }
    }
}
