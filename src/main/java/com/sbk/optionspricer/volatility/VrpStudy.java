package com.sbk.optionspricer.volatility;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Implied volatility against the volatility that followed. For each day, the VIX (a 30-day implied volatility of the S&amp;P 500)
 * is set against SPY's realised volatility over the next {@code horizon} trading days, which is what a one-month option
 * seller is paid for and what it must pay out. The premium is {@code IV - RV} in vol points; the payoff is that of a short
 * variance swap sized at one dollar a vol point when it is struck, {@code (IV^2 - RV^2) / (2 IV)}, which is close to the
 * premium when the two are close and loses much more when realised volatility spikes.
 *
 * <p>This is a study of the premium, not a backtest of any trade: no options prices, no spreads or fees, no margin, and the
 * VIX is the S&amp;P 500's, not SPY's. Daily observations overlap (each window shares 20 days with its neighbour), so they are
 * not independent; the "monthly" rows step a full horizon and do not overlap.
 */
public final class VrpStudy {

    public record Close(LocalDate date, double close) {}

    /** @param index the day's position in the closes, which is how non-overlapping windows are chosen */
    public record Observation(LocalDate date, int index, double impliedVol, double realizedVol) {
        public double premiumPoints() {
            return 100.0 * (impliedVol - realizedVol);
        }

        public double payoffPoints() {
            return 100.0 * (impliedVol * impliedVol - realizedVol * realizedVol) / (2.0 * impliedVol);
        }
    }

    public record YearRow(int year, int n, double meanPremiumPoints, double shareWithPositivePremium, double worstPayoffPoints) {}

    public record Bucket(String label, int n, double meanPremiumPoints, double shareWithPositivePremium, double worstPayoffPoints) {}

    public record MonthlyResult(int months, double meanPayoffPoints, double stdPayoffPoints, double shareNegative, double worstPayoffPoints,
                                LocalDate worstDate, double maxDrawdownPoints, List<Observation> worstFive) {}

    public record Result(int observations, LocalDate first, LocalDate last, double meanImplied, double meanRealized,
                         double meanPremiumPoints, double medianPremiumPoints, double minPremiumPoints, double maxPremiumPoints,
                         double shareWithPositivePremium, List<Double> premiumQuantilesPoints, List<YearRow> years, List<Bucket> buckets,
                         MonthlyResult monthly) {}

    private static final double TRADING_DAYS = 252.0;

    private VrpStudy() {}

    /** The VIX's daily closes, from the CBOE's {@code VIX_History.csv} (DATE,OPEN,HIGH,LOW,CLOSE; dates as MM/dd/yyyy). */
    public static Map<LocalDate, Double> parseVixCsv(List<String> lines) {
        Map<LocalDate, Double> vix = new HashMap<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        for (String line : lines) {
            String[] cols = line.split(",", -1);
            if (cols.length < 5) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(cols[0].trim(), f);
                double close = Double.parseDouble(cols[4].trim());
                if (Double.isFinite(close) && close > 0.0) {
                    vix.put(date, close);
                }
            } catch (RuntimeException skip) {
                // the header, a blank, or a damaged row
            }
        }
        return vix;
    }

    /**
     * One observation per day that has a VIX reading and {@code horizon} returns after it. The implied volatility is the day's
     * own; the realised volatility uses only the days after it.
     */
    public static List<Observation> observations(List<Close> closes, Map<LocalDate, Double> vix, int horizon) {
        if (closes == null || vix == null || horizon < 5 || closes.size() <= horizon) {
            throw new IllegalArgumentException("need more closes than the horizon (" + horizon + "), and a VIX series");
        }
        List<Close> sorted = new ArrayList<>(closes);
        sorted.sort(Comparator.comparing(Close::date));
        int n = sorted.size();
        double[] logReturn = new double[n];
        for (int i = 1; i < n; i++) {
            logReturn[i] = Math.log(sorted.get(i).close() / sorted.get(i - 1).close());
        }
        List<Observation> out = new ArrayList<>();
        for (int i = 0; i + horizon < n; i++) {
            Double level = vix.get(sorted.get(i).date());
            if (level == null || spansAHole(sorted, i, i + horizon)) {
                continue;
            }
            double sumSq = 0.0;
            for (int j = i + 1; j <= i + horizon; j++) {
                sumSq += logReturn[j] * logReturn[j];
            }
            out.add(new Observation(sorted.get(i).date(), i, level / 100.0, Math.sqrt(sumSq / horizon * TRADING_DAYS)));
        }
        return out;
    }

    /** The longest gap between consecutive trading days that is a holiday or a long weekend; more than this is missing data. */
    private static final long MAX_CALENDAR_GAP_DAYS = 7;

    /** True when two neighbouring closes in [from, to] are more than a week apart: the series has a hole there, not a quiet market. */
    private static boolean spansAHole(List<Close> closes, int from, int to) {
        for (int j = from + 1; j <= to; j++) {
            if (java.time.temporal.ChronoUnit.DAYS.between(closes.get(j - 1).date(), closes.get(j).date()) > MAX_CALENDAR_GAP_DAYS) {
                return true;
            }
        }
        return false;
    }

    /** @param stepDays the stride of the non-overlapping rows: the horizon */
    public static Result study(List<Observation> observations, int stepDays) {
        if (observations == null || observations.isEmpty() || stepDays < 1) {
            throw new IllegalArgumentException("no observations to study");
        }
        List<Observation> obs = new ArrayList<>(observations);
        obs.sort(Comparator.comparingInt(Observation::index));
        double[] premiums = obs.stream().mapToDouble(Observation::premiumPoints).toArray();
        double sumImplied = obs.stream().mapToDouble(Observation::impliedVol).sum();
        double sumRealized = obs.stream().mapToDouble(Observation::realizedVol).sum();
        double[] sortedPremiums = premiums.clone();
        java.util.Arrays.sort(sortedPremiums);
        int n = obs.size();

        List<Double> quantiles = new ArrayList<>();
        for (double q : new double[]{0.05, 0.25, 0.50, 0.75, 0.95}) {
            quantiles.add(quantile(sortedPremiums, q));
        }

        TreeMap<Integer, List<Observation>> byYear = new TreeMap<>();
        for (Observation o : obs) {
            byYear.computeIfAbsent(o.date().getYear(), y -> new ArrayList<>()).add(o);
        }
        List<YearRow> years = new ArrayList<>();
        byYear.forEach((year, list) -> years.add(new YearRow(year, list.size(), meanPremium(list), shareWithPositive(list), worstPayoff(list))));

        String[] labels = {"VIX under 15", "VIX 15 to 20", "VIX 20 to 30", "VIX 30 and over"};
        List<List<Observation>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Observation o : obs) {
            double level = o.impliedVol() * 100.0;
            groups.get(level < 15.0 ? 0 : level < 20.0 ? 1 : level < 30.0 ? 2 : 3).add(o);
        }
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            List<Observation> g = groups.get(i);
            buckets.add(new Bucket(labels[i], g.size(), g.isEmpty() ? Double.NaN : meanPremium(g),
                    g.isEmpty() ? Double.NaN : shareWithPositive(g), g.isEmpty() ? Double.NaN : worstPayoff(g)));
        }

        return new Result(n, obs.get(0).date(), obs.get(n - 1).date(), sumImplied / n, sumRealized / n,
                mean(premiums), quantile(sortedPremiums, 0.5), sortedPremiums[0], sortedPremiums[n - 1],
                shareWithPositive(obs), List.copyOf(quantiles), List.copyOf(years), List.copyOf(buckets), monthly(obs, stepDays));
    }

    private static MonthlyResult monthly(List<Observation> sortedByIndex, int stepDays) {
        List<Observation> months = new ArrayList<>();
        int lastIndex = Integer.MIN_VALUE / 2;
        for (Observation o : sortedByIndex) {
            if (o.index() >= lastIndex + stepDays) {
                months.add(o);
                lastIndex = o.index();
            }
        }
        double[] payoffs = months.stream().mapToDouble(Observation::payoffPoints).toArray();
        double mean = mean(payoffs);
        double variance = 0.0;
        for (double p : payoffs) {
            variance += (p - mean) * (p - mean);
        }
        double std = payoffs.length > 1 ? Math.sqrt(variance / (payoffs.length - 1)) : 0.0;
        long negative = java.util.Arrays.stream(payoffs).filter(p -> p < 0.0).count();
        double cumulative = 0.0, peak = 0.0, maxDrawdown = 0.0;
        for (double p : payoffs) {
            cumulative += p;
            peak = Math.max(peak, cumulative);
            maxDrawdown = Math.max(maxDrawdown, peak - cumulative);
        }
        Observation worst = months.stream().min(Comparator.comparingDouble(Observation::payoffPoints)).orElseThrow();
        List<Observation> worstFive = months.stream().sorted(Comparator.comparingDouble(Observation::payoffPoints)).limit(5).toList();
        return new MonthlyResult(months.size(), mean, std, negative / (double) months.size(), worst.payoffPoints(), worst.date(),
                maxDrawdown, worstFive);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double meanPremium(List<Observation> list) {
        return list.stream().mapToDouble(Observation::premiumPoints).average().orElse(Double.NaN);
    }

    private static double shareWithPositive(List<Observation> list) {
        return list.stream().filter(o -> o.premiumPoints() > 0.0).count() / (double) list.size();
    }

    private static double worstPayoff(List<Observation> list) {
        return list.stream().mapToDouble(Observation::payoffPoints).min().orElse(Double.NaN);
    }

    /** Linear interpolation between the closest ranks of an ascending array. */
    private static double quantile(double[] ascending, double q) {
        double position = q * (ascending.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        return ascending[lower] + (ascending[upper] - ascending[lower]) * (position - lower);
    }
}
