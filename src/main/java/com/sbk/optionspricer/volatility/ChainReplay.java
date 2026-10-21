package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.ImpliedVolatilitySolver;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.volatility.OptionHistoryStudy.Quotes;
import com.sbk.optionspricer.volatility.VrpStudy.Close;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Replays the engine's own signal on days of real option prices and asks the question that decides whether the strategy is worth running: did the
 * edge predict anything? On each decision date it takes the front straddle at the strike nearest the spot, backs the implied volatility out of the
 * two closing prices, subtracts an EWMA forecast of realized volatility from the closes up to that day (the same forecast the live engine reports),
 * and records what a buyer of that straddle made a week later and at expiry. {@link #analyse} then relates edge to result: the correlation and slope
 * over all days, the result by tercile of edge, and what the engine's rule (buy below minus the band, sell above plus it) would have made.
 *
 * <p>What it is not: the closes carry no bid or ask, so costs are an assumption charged once; the decision uses only the data of its own day, but
 * the choice of window and band is the author's, so a good-looking number here would still be one result in one market. Decision dates a week
 * apart on 28-day options overlap, so the number of independent observations is about {@code n / (28 / 7)}, and the t-statistic uses that.
 */
public final class ChainReplay {

    private static final String UNDERLYING = "SPY";
    private static final int FORECAST_WINDOW = 250;
    private static final int MIN_RETURNS = RealizedVolForecast.MIN_EWMA_RETURNS;

    @FunctionalInterface
    public interface Pricer {
        /** The closes of both legs on every day in [from, to] on which both traded. */
        NavigableMap<LocalDate, Quotes> closes(String callSymbol, String putSymbol, LocalDate from, LocalDate to);
    }

    /** One decision date. {@code exitStraddle} and {@code exit} are NaN and null when no close exists a week on and before expiry. */
    public record Observation(LocalDate entry, LocalDate expiry, double spot, double strike, double impliedVol, double forecastVol,
                              double entryStraddle, LocalDate exit, double exitStraddle, double payoffAtExpiry) {
        /** Market minus forecast, as a volatility fraction. */
        public double edge() {
            return impliedVol - forecastVol;
        }

        /** What a buyer made holding to expiry, per share, before costs. */
        public double longPnlAtExpiry() {
            return payoffAtExpiry - entryStraddle;
        }

        /** What a buyer made closing at the exit close, per share, before costs. */
        public double longPnlAtExit() {
            return exitStraddle - entryStraddle;
        }
    }

    public record Tercile(int n, double meanEdge, double meanLongPnl, double standardError) {}

    public record Rule(int n, double meanPnl, double standardError) {}

    public record Analysis(int n, double effectiveN, double meanEdge, double meanLongPnlAtExpiry, double correlation, double slopeOfPnlOnEdge,
                           double slopeTStat, List<Tercile> terciles, Rule longRule, Rule shortRule) {}

    private ChainReplay() {}

    /** Every Monday from {@code from} to {@code to}. */
    public static List<LocalDate> mondays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        while (!d.isAfter(to)) {
            out.add(d);
            d = d.plusWeeks(1);
        }
        return out;
    }

    /**
     * @param targetDays calendar days to expiry wanted: the Friday expiry on or after entry + targetDays - 3
     * @param holdDays   calendar days after entry at which the exit close is taken (the first close on or after that day)
     */
    public static List<Observation> replay(List<Close> spy, List<LocalDate> decisionDates, int targetDays, int holdDays, double lambda,
                                           double riskFreeRate, double dividendYield, Pricer pricer) {
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        for (Close c : spy) {
            closes.put(c.date(), c.close());
        }
        List<Observation> out = new ArrayList<>();
        for (LocalDate requested : decisionDates) {
            Map.Entry<LocalDate, Double> entryClose = closes.floorEntry(requested);
            if (entryClose == null) {
                continue;
            }
            LocalDate entry = entryClose.getKey();
            double forecast = forecastAt(closes, entry, lambda);
            if (!Double.isFinite(forecast)) {
                continue;
            }
            LocalDate expiry = entry.plusDays(Math.max(1, targetDays - 3)).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
            Map.Entry<LocalDate, Double> expiryClose = closes.floorEntry(expiry);
            if (expiryClose == null || expiry.isAfter(closes.lastKey().plusDays(4))) {
                continue; // the expiry is beyond the data: the result is not known yet
            }
            double spot = entryClose.getValue();
            double strike = Math.rint(spot);
            String call = OccSymbol.format(UNDERLYING, expiry, OptionType.CALL, strike);
            String put = OccSymbol.format(UNDERLYING, expiry, OptionType.PUT, strike);
            NavigableMap<LocalDate, Quotes> prices;
            try {
                prices = pricer.closes(call, put, entry, entry.plusDays(holdDays + 4L));
            } catch (RuntimeException e) {
                continue;
            }
            Quotes atEntry = prices == null ? null : prices.get(entry);
            if (atEntry == null) {
                continue;
            }
            double t = (expiry.toEpochDay() - entry.toEpochDay()) / 365.0;
            OptionalDouble callIv = impliedVol(OptionType.CALL, spot, strike, t, riskFreeRate, dividendYield, atEntry.call());
            OptionalDouble putIv = impliedVol(OptionType.PUT, spot, strike, t, riskFreeRate, dividendYield, atEntry.put());
            if (callIv.isEmpty() || putIv.isEmpty()) {
                continue;
            }
            Map.Entry<LocalDate, Quotes> exitQuote = prices.ceilingEntry(entry.plusDays(holdDays));
            LocalDate exit = exitQuote == null || !exitQuote.getKey().isBefore(expiry) ? null : exitQuote.getKey();
            double exitStraddle = exit == null ? Double.NaN : exitQuote.getValue().call() + exitQuote.getValue().put();
            double payoff = Math.abs(expiryClose.getValue() - strike);
            out.add(new Observation(entry, expiry, spot, strike, (callIv.getAsDouble() + putIv.getAsDouble()) / 2.0, forecast,
                    atEntry.call() + atEntry.put(), exit, exitStraddle, payoff));
        }
        return List.copyOf(out);
    }

    private static OptionalDouble impliedVol(OptionType type, double spot, double strike, double t, double r, double q, double price) {
        if (!(price > 0.0) || !(t > 0.0)) {
            return OptionalDouble.empty();
        }
        return ImpliedVolatilitySolver.solve(type, new OptionParameters(spot, strike, t, r, 0.2, q), price);
    }

    /** The EWMA forecast of annual volatility from the closes up to and including {@code day}, NaN until enough returns exist. */
    private static double forecastAt(TreeMap<LocalDate, Double> closes, LocalDate day, double lambda) {
        List<Double> prices = new ArrayList<>(closes.headMap(day, true).values());
        if (prices.size() < MIN_RETURNS + 1) {
            return Double.NaN;
        }
        prices = prices.subList(Math.max(0, prices.size() - FORECAST_WINDOW), prices.size());
        double[] returns = new double[prices.size() - 1];
        for (int i = 1; i < prices.size(); i++) {
            returns[i - 1] = Math.log(prices.get(i) / prices.get(i - 1));
        }
        return RealizedVolForecast.ewmaVol(returns, lambda, RealizedVolForecast.TRADING_DAYS_PER_YEAR);
    }

    /**
     * @param costPerLeg  charged once on each of the two legs, per share
     * @param band        the engine's edge band in volatility fraction (0.01 is one point)
     * @param targetDays  and @param stepDays: how far the options run and how far apart the decisions are, which fix how much the observations overlap
     */
    public static Analysis analyse(List<Observation> observations, double costPerLeg, double band, int targetDays, int stepDays) {
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("no observations to analyse");
        }
        List<Observation> ordered = new ArrayList<>(observations);
        ordered.sort(Comparator.comparingDouble(Observation::edge));
        int n = ordered.size();
        double overlap = Math.max(1.0, (double) targetDays / Math.max(1, stepDays));
        double effectiveN = n / overlap;

        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = ordered.get(i).edge();
            y[i] = ordered.get(i).longPnlAtExpiry() - 2 * costPerLeg;
        }
        double meanX = mean(x), meanY = mean(y);
        double sxx = 0, sxy = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (x[i] - meanX) * (x[i] - meanX);
            sxy += (x[i] - meanX) * (y[i] - meanY);
            syy += (y[i] - meanY) * (y[i] - meanY);
        }
        double slope = sxx > 0 ? sxy / sxx : 0.0;
        double correlation = sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : 0.0;
        double slopeT = 0.0;
        if (effectiveN > 2 && sxx > 0 && Math.abs(correlation) < 1.0) {
            // t of a correlation with the effective sample size, not n: overlapping options are not independent draws
            slopeT = correlation * Math.sqrt((effectiveN - 2.0) / (1.0 - correlation * correlation));
        }

        List<Tercile> terciles = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            int from = n * k / 3, to = n * (k + 1) / 3;
            double[] edges = new double[to - from], pnl = new double[to - from];
            for (int i = from; i < to; i++) {
                edges[i - from] = x[i];
                pnl[i - from] = y[i];
            }
            terciles.add(new Tercile(to - from, edges.length == 0 ? Double.NaN : mean(edges), edges.length == 0 ? Double.NaN : mean(pnl),
                    standardError(pnl, overlap)));
        }

        List<Double> longPnl = new ArrayList<>(), shortPnl = new ArrayList<>();
        for (Observation o : ordered) {
            if (o.edge() < -band) {
                longPnl.add(o.longPnlAtExpiry() - 2 * costPerLeg);
            } else if (o.edge() > band) {
                shortPnl.add(-o.longPnlAtExpiry() - 2 * costPerLeg);
            }
        }
        return new Analysis(n, effectiveN, mean(x), meanY, correlation, slope, slopeT, List.copyOf(terciles), rule(longPnl, overlap), rule(shortPnl, overlap));
    }

    private static Rule rule(List<Double> pnl, double overlap) {
        if (pnl.isEmpty()) {
            return new Rule(0, Double.NaN, Double.NaN);
        }
        double[] a = pnl.stream().mapToDouble(Double::doubleValue).toArray();
        return new Rule(a.length, mean(a), standardError(a, overlap));
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return a.length == 0 ? Double.NaN : s / a.length;
    }

    /** Standard error of the mean with the sample size cut by the overlap, so a result built from overlapping options is not flattered. */
    private static double standardError(double[] a, double overlap) {
        if (a.length < 2) {
            return Double.NaN;
        }
        double m = mean(a), ss = 0;
        for (double v : a) ss += (v - m) * (v - m);
        double sd = Math.sqrt(ss / (a.length - 1));
        return sd / Math.sqrt(Math.max(1.0, a.length / overlap));
    }
}
