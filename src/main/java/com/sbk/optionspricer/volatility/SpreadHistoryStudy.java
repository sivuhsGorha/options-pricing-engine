package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.volatility.VrpStudy.Close;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Selling a defined-risk spread on a calendar, at real daily option closes, held to expiry: what it collected, what the expiry took back, and whether the
 * result beat zero by more than luck. A put credit spread sells a put a set distance below the spot and buys one a fixed width lower; an iron condor adds the
 * mirror call spread above. The strikes are the dollars nearest the chosen distance from the spot that day, so no chain listing is needed.
 *
 * <p>The pass rule is written here, before any run, and {@link #evaluate} applies it without discretion: a positive mean after costs, a t-statistic above 2,
 * a positive mean in every calendar year, and a worst trade the account can afford. Meeting the first and missing the second is "UNPROVEN", not a pass,
 * which with about thirty monthly trades is the likely outcome for any small premium. A daily close has no bid or ask (costs are assumed, charged once per
 * leg), early exercise and dividends are ignored, and the data is one market over a few years.
 */
public final class SpreadHistoryStudy {

    private static final String UNDERLYING = "SPY";
    /** Shares per contract: a per-share result times this is the dollars of one spread. */
    public static final int CONTRACT_SIZE = 100;

    public enum Kind { PUT_SPREAD, IRON_CONDOR }

    @FunctionalInterface
    public interface Pricer {
        /**
         * The closes of the legs on the entry day, in the order of {@code symbols}; empty when any leg did not trade. The order is long put, short put
         * for a put spread, and long put, short put, short call, long call for an iron condor.
         */
        Optional<double[]> closes(List<String> symbols, LocalDate entry);
    }

    /**
     * Prices are per share. {@code strikes} are in the order of the legs (see {@link Pricer}).
     */
    public record Trade(Kind kind, LocalDate entry, LocalDate expiry, double spotEntry, double spotExpiry, double[] strikes, double credit) {

        public int legCount() {
            return kind == Kind.PUT_SPREAD ? 2 : 4;
        }

        /** The put side's width in dollars: the most that side can cost. */
        public double width() {
            return strikes[1] - strikes[0];
        }

        /** What the expiry takes back from the seller. */
        public double payoffAtExpiry() {
            double put = Math.max(0.0, strikes[1] - spotExpiry) - Math.max(0.0, strikes[0] - spotExpiry);
            if (kind == Kind.PUT_SPREAD) {
                return put;
            }
            double call = Math.max(0.0, spotExpiry - strikes[2]) - Math.max(0.0, spotExpiry - strikes[3]);
            return put + call;
        }

        public double grossPnl() {
            return credit - payoffAtExpiry();
        }

        /** The worst the trade can do before costs, a positive number: the width less the credit. */
        public double maxLoss() {
            return width() - credit;
        }

        /** The result per share after {@code costPerLeg} on every leg, once, on the way in. */
        public double pnl(double costPerLeg) {
            return grossPnl() - legCount() * costPerLeg;
        }
    }

    public record Result(List<Trade> trades, int skippedNoPrice) {}

    public record Summary(int n, double meanPnl, double medianPnl, double stdPnl, double winRate, double worstPnl, double bestPnl, double maxDrawdown,
                          double meanCredit, double meanMaxLoss, double tStatistic, double effectiveN) {}

    public record YearRow(int year, int n, double meanPnl) {}

    /** The pass rule's four conditions and the verdict they give. */
    public record Verdict(boolean positiveMean, boolean tStatisticAboveTwo, boolean everyYearPositive, boolean worstTradeAffordable, double meanPnl,
                          double tStatistic, double worstTradeDollars) {
        public String overall() {
            if (!positiveMean || !worstTradeAffordable) {
                return "FAIL";
            }
            return tStatisticAboveTwo && everyYearPositive ? "PASS" : "UNPROVEN";
        }
    }

    private SpreadHistoryStudy() {}

    /**
     * One trade per expiry, entered {@code daysBefore} calendar days earlier (the last trading day on or before) with the short strikes the dollars nearest
     * {@code distance} (0.03 is 3%) from that day's spot and the long strikes {@code width} dollars beyond them. An expiry outside the data is left out; one
     * whose legs have no price, or whose credit is not positive, is counted as skipped.
     */
    public static Result simulate(List<Close> spy, Kind kind, List<LocalDate> expiries, int daysBefore, double distance, double width, Pricer pricer) {
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        for (Close c : spy) {
            closes.put(c.date(), c.close());
        }
        List<Trade> trades = new ArrayList<>();
        int skipped = 0;
        for (LocalDate expiry : expiries) {
            Map.Entry<LocalDate, Double> expiryClose = closes.floorEntry(expiry);
            if (expiryClose == null || expiry.toEpochDay() - expiryClose.getKey().toEpochDay() > 4 || expiry.isAfter(closes.lastKey().plusDays(4))) {
                continue;
            }
            LocalDate actualExpiry = expiryClose.getKey();
            Map.Entry<LocalDate, Double> entryClose = closes.floorEntry(actualExpiry.minusDays(daysBefore));
            if (entryClose == null) {
                continue;
            }
            double spot = entryClose.getValue();
            double shortPut = Math.rint(spot * (1.0 - distance));
            double longPut = shortPut - width;
            double[] strikes = kind == Kind.PUT_SPREAD
                    ? new double[] {longPut, shortPut}
                    : new double[] {longPut, shortPut, Math.rint(spot * (1.0 + distance)), Math.rint(spot * (1.0 + distance)) + width};
            if (longPut <= 0.0) {
                continue;
            }
            List<String> symbols = new ArrayList<>();
            symbols.add(OccSymbol.format(UNDERLYING, actualExpiry, OptionType.PUT, strikes[0]));
            symbols.add(OccSymbol.format(UNDERLYING, actualExpiry, OptionType.PUT, strikes[1]));
            if (kind == Kind.IRON_CONDOR) {
                symbols.add(OccSymbol.format(UNDERLYING, actualExpiry, OptionType.CALL, strikes[2]));
                symbols.add(OccSymbol.format(UNDERLYING, actualExpiry, OptionType.CALL, strikes[3]));
            }
            Optional<double[]> prices = pricer.closes(symbols, entryClose.getKey());
            if (prices.isEmpty() || prices.get().length != symbols.size()) {
                skipped++;
                continue;
            }
            double[] p = prices.get();
            double credit = (p[1] - p[0]) + (kind == Kind.IRON_CONDOR ? (p[2] - p[3]) : 0.0);
            if (!(credit > 0.0)) {
                skipped++;
                continue;
            }
            trades.add(new Trade(kind, entryClose.getKey(), actualExpiry, spot, expiryClose.getValue(), strikes, credit));
        }
        return new Result(List.copyOf(trades), skipped);
    }

    /**
     * @param overlap how many of the trades are open at once (1 for monthly, about 4 for weekly 28-day ones): the t-statistic uses {@code n / overlap}
     *                observations because overlapping trades are not independent
     */
    public static Summary summarise(List<Trade> trades, double costPerLeg, double overlap) {
        if (trades == null || trades.isEmpty()) {
            throw new IllegalArgumentException("no trades to summarise");
        }
        List<Trade> ordered = new ArrayList<>(trades);
        ordered.sort(Comparator.comparing(Trade::entry));
        double[] pnl = ordered.stream().mapToDouble(t -> t.pnl(costPerLeg)).toArray();
        int n = pnl.length;
        double mean = Arrays.stream(pnl).average().orElse(Double.NaN);
        double[] sorted = pnl.clone();
        Arrays.sort(sorted);
        double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        double variance = 0.0;
        for (double p : pnl) {
            variance += (p - mean) * (p - mean);
        }
        double std = n > 1 ? Math.sqrt(variance / (n - 1)) : 0.0;
        double effectiveN = n / Math.max(1.0, overlap);
        double t = std > 0.0 && effectiveN > 1.0 ? mean / (std / Math.sqrt(effectiveN)) : 0.0;
        long wins = Arrays.stream(pnl).filter(p -> p > 0.0).count();
        double cumulative = 0.0, peak = 0.0, maxDrawdown = 0.0;
        for (double p : pnl) {
            cumulative += p;
            peak = Math.max(peak, cumulative);
            maxDrawdown = Math.max(maxDrawdown, peak - cumulative);
        }
        return new Summary(n, mean, median, std, wins / (double) n, sorted[0], sorted[n - 1], maxDrawdown,
                ordered.stream().mapToDouble(Trade::credit).average().orElse(Double.NaN),
                ordered.stream().mapToDouble(Trade::maxLoss).average().orElse(Double.NaN), t, effectiveN);
    }

    public static List<YearRow> byYear(List<Trade> trades, double costPerLeg) {
        TreeMap<Integer, List<Trade>> years = new TreeMap<>();
        for (Trade t : trades) {
            years.computeIfAbsent(t.entry().getYear(), y -> new ArrayList<>()).add(t);
        }
        List<YearRow> rows = new ArrayList<>();
        years.forEach((year, list) -> rows.add(new YearRow(year, list.size(), list.stream().mapToDouble(t -> t.pnl(costPerLeg)).average().orElse(Double.NaN))));
        return List.copyOf(rows);
    }

    /** Applies the pass rule. {@code maxAffordableLossDollars} is the most one spread may lose, in dollars for one contract. */
    public static Verdict evaluate(List<Trade> trades, double costPerLeg, double overlap, double maxAffordableLossDollars) {
        Summary s = summarise(trades, costPerLeg, overlap);
        boolean everyYear = byYear(trades, costPerLeg).stream().allMatch(y -> y.meanPnl() > 0.0);
        double worstDollars = -s.worstPnl() * CONTRACT_SIZE;
        return new Verdict(s.meanPnl() > 0.0, s.tStatistic() > 2.0, everyYear, worstDollars <= maxAffordableLossDollars, s.meanPnl(), s.tStatistic(), worstDollars);
    }
}
