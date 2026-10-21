package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.volatility.VrpStudy.Close;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A straddle bought on a fixed calendar at the closing prices of its day and held to expiry, on real daily option prices: what it cost,
 * what the expiry paid, so what a buyer made and a seller would have made. The strike is the dollar nearest the spot. Hold-to-expiry on a
 * calendar is not this engine's strategy, and a daily close has no bid or ask (costs are an assumption, charged once, on the way in); it
 * is a screen for whether the volatility premium survives costs on real option prices, not a test of the engine.
 */
public final class OptionHistoryStudy {

    private static final String UNDERLYING = "SPY";

    /** The day's closing prices of the two legs. */
    public record Quotes(double call, double put) {}

    @FunctionalInterface
    public interface Pricer {
        /** The legs' closes on {@code entry}, or empty when either did not trade that day. */
        Optional<Quotes> price(String callSymbol, String putSymbol, LocalDate entry);
    }

    /** Prices are per share; a contract is 100 shares. */
    public record Trade(LocalDate entry, LocalDate expiry, double spotEntry, double spotExpiry, double strike, double callEntry, double putEntry,
                        double straddlePrice, double payoffAtExpiry) {
        public double grossLongPnl() {
            return payoffAtExpiry - straddlePrice;
        }

        /** A buyer's result, less {@code costPerLeg} on each of the two legs on the way in. */
        public double longPnl(double costPerLeg) {
            return grossLongPnl() - 2.0 * costPerLeg;
        }

        /** A seller's result: the mirror, less the same costs. */
        public double shortPnl(double costPerLeg) {
            return -grossLongPnl() - 2.0 * costPerLeg;
        }
    }

    public record Result(List<Trade> trades, int skippedNoPrice) {}

    public record Summary(int n, double meanPnl, double medianPnl, double stdPnl, double winRate, double worstPnl, double bestPnl, double maxDrawdown,
                          double meanStraddlePrice, double meanPayoff) {}

    public record YearRow(int year, int n, double longMeanPnl, double shortMeanPnl) {}

    private OptionHistoryStudy() {}

    /** The third Friday of each month from {@code from} to {@code to}: SPY's monthly expiry. */
    public static List<LocalDate> thirdFridays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate month = from.withDayOfMonth(1);
        while (!month.isAfter(to)) {
            LocalDate third = month.with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY));
            if (!third.isBefore(from) && !third.isAfter(to)) {
                out.add(third);
            }
            month = month.plusMonths(1);
        }
        return out;
    }

    /** Every Friday from {@code from} to {@code to}: SPY's weekly expiry. */
    public static List<LocalDate> fridays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        while (!d.isAfter(to)) {
            out.add(d);
            d = d.plusWeeks(1);
        }
        return out;
    }

    /**
     * One trade per expiry: entered {@code daysBefore} calendar days earlier (the last trading day on or before that), at the strike nearest
     * the spot that day, held to expiry. An expiry that is not a trading day (a holiday) moves to the day before. An expiry whose entry day or
     * expiry day is not in the data is left out; one whose legs have no price is counted.
     */
    public static Result simulate(List<Close> spy, List<LocalDate> expiries, int daysBefore, Pricer pricer) {
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        for (Close c : spy) {
            closes.put(c.date(), c.close());
        }
        List<Trade> trades = new ArrayList<>();
        int skipped = 0;
        for (LocalDate expiry : expiries) {
            Map.Entry<LocalDate, Double> expiryClose = closes.floorEntry(expiry);
            if (expiryClose == null || expiry.toEpochDay() - expiryClose.getKey().toEpochDay() > 4 || expiry.isAfter(closes.lastKey().plusDays(4))) {
                continue; // no trading day at or just before the expiry within the data
            }
            LocalDate actualExpiry = expiryClose.getKey();
            Map.Entry<LocalDate, Double> entryClose = closes.floorEntry(actualExpiry.minusDays(daysBefore));
            if (entryClose == null) {
                continue;
            }
            double spot = entryClose.getValue();
            double strike = Math.rint(spot);
            String call = OccSymbol.format(UNDERLYING, actualExpiry, OptionType.CALL, strike);
            String put = OccSymbol.format(UNDERLYING, actualExpiry, OptionType.PUT, strike);
            Optional<Quotes> quotes = pricer.price(call, put, entryClose.getKey());
            if (quotes.isEmpty()) {
                skipped++;
                continue;
            }
            double spotAtExpiry = expiryClose.getValue();
            double payoff = Math.abs(spotAtExpiry - strike);
            trades.add(new Trade(entryClose.getKey(), actualExpiry, spot, spotAtExpiry, strike, quotes.get().call(), quotes.get().put(),
                    quotes.get().call() + quotes.get().put(), payoff));
        }
        return new Result(List.copyOf(trades), skipped);
    }

    public static Summary summarise(List<Trade> trades, double costPerLeg, boolean buyer) {
        if (trades == null || trades.isEmpty()) {
            throw new IllegalArgumentException("no trades to summarise");
        }
        List<Trade> ordered = new ArrayList<>(trades);
        ordered.sort(Comparator.comparing(Trade::entry));
        double[] pnl = ordered.stream().mapToDouble(t -> buyer ? t.longPnl(costPerLeg) : t.shortPnl(costPerLeg)).toArray();
        int n = pnl.length;
        double mean = java.util.Arrays.stream(pnl).average().orElse(Double.NaN);
        double[] sorted = pnl.clone();
        java.util.Arrays.sort(sorted);
        double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        double variance = 0.0;
        for (double p : pnl) {
            variance += (p - mean) * (p - mean);
        }
        double std = n > 1 ? Math.sqrt(variance / (n - 1)) : 0.0;
        long wins = java.util.Arrays.stream(pnl).filter(p -> p > 0.0).count();
        double cumulative = 0.0, peak = 0.0, maxDrawdown = 0.0;
        for (double p : pnl) {
            cumulative += p;
            peak = Math.max(peak, cumulative);
            maxDrawdown = Math.max(maxDrawdown, peak - cumulative);
        }
        return new Summary(n, mean, median, std, wins / (double) n, sorted[0], sorted[n - 1], maxDrawdown,
                ordered.stream().mapToDouble(Trade::straddlePrice).average().orElse(Double.NaN),
                ordered.stream().mapToDouble(Trade::payoffAtExpiry).average().orElse(Double.NaN));
    }

    public static List<YearRow> byYear(List<Trade> trades, double costPerLeg) {
        TreeMap<Integer, List<Trade>> years = new TreeMap<>();
        for (Trade t : trades) {
            years.computeIfAbsent(t.entry().getYear(), y -> new ArrayList<>()).add(t);
        }
        List<YearRow> rows = new ArrayList<>();
        years.forEach((year, list) -> rows.add(new YearRow(year, list.size(),
                list.stream().mapToDouble(t -> t.longPnl(costPerLeg)).average().orElse(Double.NaN),
                list.stream().mapToDouble(t -> t.shortPnl(costPerLeg)).average().orElse(Double.NaN))));
        return List.copyOf(rows);
    }
}
