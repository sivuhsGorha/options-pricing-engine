package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;
import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.volatility.DailyCloseFeed;
import com.sbk.optionspricer.volatility.RealizedVolForecast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Replays recorded chain snapshots against a realized-volatility forecast: sell the front-expiry at-the-money
 * straddle when implied vol is rich to the forecast, buy it when cheap, close it when the gap has reverted.
 *
 * <p>How the replay is built, and what it does not claim.
 * <ul>
 *   <li><b>Time.</b> Snapshots with the same timestamp are one moment, whatever their expiry: the recorder keeps every
 *   expiry of a chain together, so the reader returns one snapshot per expiry per instant. At each moment the replay
 *   looks at one chain: the expiry the position was opened on, or, when flat, the nearest expiry at least
 *   {@link ReplayConfig#minDaysToExpiry()} days out (the strategy's own rule).</li>
 *   <li><b>Marks.</b> A position is priced at its own strike, not at whichever strike is at the money now, and a
 *   position still open at the end is closed at the last quotes seen for it.</li>
 *   <li><b>Fills.</b> At the touch: a short is opened at the bids and closed at the asks (the reverse for a long), with a
 *   fee per contract on every leg of every trade, so a signal that never pays loses its spread.</li>
 *   <li><b>Realized volatility.</b> From daily closes strictly before the snapshot's day (no look-ahead). The replay
 *   never invents closes: with none it cannot run.</li>
 * </ul>
 * Not modelled: slippage beyond the touch, partial fills, assignment, any hedge of the delta, and the fact that the
 * forward and the rate are assumed. A few days of snapshots says nothing about whether a signal predicts anything; the
 * result reports how many moments and days it covers so that is visible.
 */
public final class VrpSignalBacktester {

    /** One recorded chain (one expiry) and the moment it describes. Several snapshots may share a moment. */
    public record Snapshot(Instant at, OptionChain chain) {}

    public record VrpTrade(
            LocalDate date,
            LocalDate expiry,
            double strike,
            boolean isShort,
            double entryStraddlePrice,
            double exitStraddlePrice,
            double entryVrp,
            double pnl
    ) {}

    public record ReplayConfig(
            double vrpThreshold,
            int contractQty,
            double feePerContract,
            boolean demeanVrp,
            int rollingWindow,
            boolean enforceCostHurdle,
            int minDaysToExpiry
    ) {
        public ReplayConfig {
            if (minDaysToExpiry < 0) {
                throw new IllegalArgumentException("minDaysToExpiry must not be negative");
            }
        }

        /** With the strategy's own default of 14 days. */
        public ReplayConfig(double vrpThreshold, int contractQty, double feePerContract, boolean demeanVrp,
                            int rollingWindow, boolean enforceCostHurdle) {
            this(vrpThreshold, contractQty, feePerContract, demeanVrp, rollingWindow, enforceCostHurdle, 14);
        }

        public static ReplayConfig defaults() {
            return new ReplayConfig(0.01, 1, 0.65, false, 0, false);
        }
    }

    /**
     * @param totalSnapshots  distinct moments in time in the input (not chains)
     * @param evaluatedSteps  moments that produced a usable straddle and forecast
     * @param daysCovered     distinct calendar days among the moments
     */
    public record VrpBacktestResult(
            int totalSnapshots,
            int totalTrades,
            int winningTrades,
            double winRate,
            double totalPnl,
            double averagePnlPerTrade,
            double maxDrawdown,
            List<VrpTrade> trades,
            int hurdlesPassed,
            int hurdlesRejected,
            double meanVrp,
            int evaluatedSteps,
            int daysCovered
    ) {
        public VrpBacktestResult(int totalSnapshots, int totalTrades, int winningTrades, double winRate,
                                 double totalPnl, double averagePnlPerTrade, double maxDrawdown, List<VrpTrade> trades) {
            this(totalSnapshots, totalTrades, winningTrades, winRate, totalPnl, averagePnlPerTrade, maxDrawdown, trades,
                    totalTrades, 0, 0.0, 0, 0);
        }
    }

    private static final double CONTRACT_MULTIPLIER = 100.0;
    private static final double RATE_FOR_VEGA = 0.05;

    private VrpSignalBacktester() {}

    /** With one forecaster held fixed for the whole replay (for short replays and tests). */
    public static VrpBacktestResult run(List<Snapshot> snapshots, RealizedVolForecast volForecast,
                                        double vrpThreshold, int contractQty, double feePerContract) {
        return run(snapshots, volForecast, new ReplayConfig(vrpThreshold, contractQty, feePerContract, false, 0, false));
    }

    public static VrpBacktestResult run(List<Snapshot> snapshots, RealizedVolForecast volForecast, ReplayConfig config) {
        if (volForecast == null) {
            return empty();
        }
        return runCore(snapshots, day -> volForecast, config);
    }

    /**
     * With a forecaster rebuilt for each day from the daily closes before that day, so the forecast on a day never
     * contains that day's own close.
     */
    public static VrpBacktestResult run(List<Snapshot> snapshots, List<DailyCloseFeed.DailyClose> closes,
                                        RealizedVolForecast.Model model, ReplayConfig config) {
        if (closes == null || closes.isEmpty() || model == null) {
            return empty();
        }
        List<DailyCloseFeed.DailyClose> sorted = closes.stream()
                .sorted(java.util.Comparator.comparing(DailyCloseFeed.DailyClose::date)).toList();
        Map<LocalDate, RealizedVolForecast> perDay = new HashMap<>();
        return runCore(snapshots, day -> perDay.computeIfAbsent(day, d -> {
            RealizedVolForecast forecast = new RealizedVolForecast(model);
            forecast.setPrices(sorted.stream().filter(c -> c.date().isBefore(d)).mapToDouble(DailyCloseFeed.DailyClose::close).toArray());
            return forecast;
        }), config);
    }

    private static VrpBacktestResult empty() {
        return new VrpBacktestResult(0, 0, 0, 0.0, 0.0, 0.0, 0.0, List.of(), 0, 0, 0.0, 0, 0);
    }

    private static VrpBacktestResult runCore(List<Snapshot> snapshots, Function<LocalDate, RealizedVolForecast> forecastFor,
                                             ReplayConfig config) {
        if (snapshots == null || snapshots.isEmpty() || config == null) {
            return empty();
        }
        // one moment may carry several expiries
        TreeMap<Instant, List<OptionChain>> moments = new TreeMap<>();
        for (Snapshot snapshot : snapshots) {
            moments.computeIfAbsent(snapshot.at(), k -> new ArrayList<>()).add(snapshot.chain());
        }
        int daysCovered = (int) moments.keySet().stream().map(t -> LocalDate.ofInstant(t, ZoneOffset.UTC)).distinct().count();

        List<VrpTrade> executedTrades = new ArrayList<>();
        double runningPnl = 0.0;
        double peakPnl = 0.0;
        double maxDrawdown = 0.0;
        double fees = 4.0 * config.feePerContract() * config.contractQty(); // two legs, in and out

        Double openPrice = null;       // what the straddle was sold for (a short) or paid for (a long), per share
        double openVrp = Double.NaN;
        LocalDate openExpiry = null;
        double openStrike = Double.NaN;
        boolean openIsShort = false;
        double lastExitPrice = Double.NaN;   // the position at the touch it would close at, from the last quotes seen
        LocalDate lastSeenDate = null;

        List<Double> vrpHistory = new ArrayList<>();
        double totalVrpSum = 0.0;
        int validVrpCount = 0;
        int hurdlesPassed = 0;
        int hurdlesRejected = 0;

        for (Map.Entry<Instant, List<OptionChain>> moment : moments.entrySet()) {
            LocalDate date = LocalDate.ofInstant(moment.getKey(), ZoneOffset.UTC);
            OptionChain chain = openPrice != null
                    ? chainWithExpiry(moment.getValue(), openExpiry)
                    : frontChain(moment.getValue(), date, config.minDaysToExpiry());
            if (chain == null) {
                continue;
            }
            OptionalDouble atmStrike = findAtmStrike(chain);
            if (atmStrike.isEmpty()) {
                continue;
            }
            double strike = atmStrike.getAsDouble();
            OptionQuote call = findQuote(chain, strike, OptionType.CALL);
            OptionQuote put = findQuote(chain, strike, OptionType.PUT);
            if (call == null || put == null || call.bid() <= 0 || put.bid() <= 0) {
                continue;
            }

            double marketAtmIv = (call.impliedVolatility() + put.impliedVolatility()) / 2.0;
            double t = Math.max(1.0 / 252.0, TimeConventions.yearFraction(date, chain.expiry()));
            RealizedVolForecast forecast = forecastFor.apply(date);
            OptionalDouble fair = forecast == null ? OptionalDouble.empty() : forecast.fairVol(chain.symbol(), chain.expiry(), strike, t);
            if (fair.isEmpty()) {
                continue;
            }
            double rawVrp = marketAtmIv - fair.getAsDouble();
            vrpHistory.add(rawVrp);
            totalVrpSum += rawVrp;
            validVrpCount++;

            double effectiveVrp = rawVrp;
            if (config.demeanVrp() && config.rollingWindow() > 0) {
                int start = Math.max(0, vrpHistory.size() - config.rollingWindow());
                double winSum = 0.0;
                int count = 0;
                for (int i = start; i < vrpHistory.size(); i++) {
                    winSum += vrpHistory.get(i);
                    count++;
                }
                effectiveVrp = rawVrp - (count > 0 ? winSum / count : 0.0);
            }

            if (openPrice != null) {
                // the position is priced at its own strike, whatever is at the money now
                OptionQuote ownCall = findQuote(chain, openStrike, OptionType.CALL);
                OptionQuote ownPut = findQuote(chain, openStrike, OptionType.PUT);
                double closeAt = closingPrice(ownCall, ownPut, openIsShort);
                if (Double.isFinite(closeAt)) {
                    lastExitPrice = closeAt;
                    lastSeenDate = date;
                }
                boolean exitShort = openIsShort && (effectiveVrp <= 0.0 || effectiveVrp < openVrp * 0.5);
                boolean exitLong = !openIsShort && (effectiveVrp >= 0.0 || effectiveVrp > openVrp * 0.5);
                if ((exitShort || exitLong) && Double.isFinite(closeAt)) {
                    double tradePnl = tradePnl(openIsShort, openPrice, closeAt, config.contractQty(), fees);
                    runningPnl += tradePnl;
                    peakPnl = Math.max(peakPnl, runningPnl);
                    maxDrawdown = Math.max(maxDrawdown, peakPnl - runningPnl);
                    executedTrades.add(new VrpTrade(date, openExpiry, openStrike, openIsShort, openPrice, closeAt, openVrp, tradePnl));
                    openPrice = null;
                }
            } else {
                // Cost-aware break-even hurdle: round-trip (half-spread + fees) / vega
                double callHalf = Math.max(0.0, (call.ask() - call.bid()) / 2.0);
                double putHalf = Math.max(0.0, (put.ask() - put.bid()) / 2.0);
                double roundTripFriction = 2.0 * (callHalf + putHalf + (2.0 * config.feePerContract() / 100.0));
                double straddleVega = 2.0 * BlackScholesPricer.vega(chain.spot(), strike, t, RATE_FOR_VEGA, marketAtmIv, 0.0);
                double breakEvenHurdle = straddleVega > 1e-6 ? (roundTripFriction / straddleVega) : 0.05;
                double hurdle = config.enforceCostHurdle() ? Math.max(config.vrpThreshold(), breakEvenHurdle) : config.vrpThreshold();
                if (effectiveVrp > hurdle || effectiveVrp < -hurdle) {
                    hurdlesPassed++;
                    openIsShort = effectiveVrp > 0.0;
                    // rich: sell the straddle at the bids; cheap: buy it at the asks
                    openPrice = openIsShort ? call.bid() + put.bid() : call.ask() + put.ask();
                    openVrp = effectiveVrp;
                    openExpiry = chain.expiry();
                    openStrike = strike;
                    lastExitPrice = closingPrice(call, put, openIsShort);
                    lastSeenDate = date;
                } else if (Math.abs(effectiveVrp) > config.vrpThreshold() && config.enforceCostHurdle()) {
                    hurdlesRejected++;
                }
            }
        }

        // a position still open at the end is closed at the last quotes seen for it, at the touch like any other exit
        if (openPrice != null) {
            double tradePnl = tradePnl(openIsShort, openPrice, lastExitPrice, config.contractQty(), fees);
            runningPnl += tradePnl;
            peakPnl = Math.max(peakPnl, runningPnl);
            maxDrawdown = Math.max(maxDrawdown, peakPnl - runningPnl);
            executedTrades.add(new VrpTrade(lastSeenDate, openExpiry, openStrike, openIsShort, openPrice, lastExitPrice, openVrp, tradePnl));
        }

        int total = executedTrades.size();
        int winners = (int) executedTrades.stream().filter(trade -> trade.pnl() > 0.0).count();
        double winRate = total > 0 ? (double) winners / total : 0.0;
        double avgPnl = total > 0 ? runningPnl / total : 0.0;
        double meanVrp = validVrpCount > 0 ? totalVrpSum / validVrpCount : 0.0;

        return new VrpBacktestResult(moments.size(), total, winners, winRate, runningPnl, avgPnl, maxDrawdown,
                executedTrades, hurdlesPassed, hurdlesRejected, meanVrp, validVrpCount, daysCovered);
    }

    /** What closing the straddle costs (a short buys it back at the asks) or fetches (a long sells it at the bids); NaN if unquoted. */
    private static double closingPrice(OptionQuote call, OptionQuote put, boolean isShort) {
        if (call == null || put == null) {
            return Double.NaN;
        }
        double price = isShort ? call.ask() + put.ask() : call.bid() + put.bid();
        return price > 0.0 && Double.isFinite(price) ? price : Double.NaN;
    }

    private static OptionChain chainWithExpiry(List<OptionChain> chains, LocalDate expiry) {
        for (OptionChain chain : chains) {
            if (chain.expiry().equals(expiry)) {
                return chain;
            }
        }
        return null;
    }

    /** The nearest expiry at least {@code minDays} out: the strategy's own choice of front month. */
    private static OptionChain frontChain(List<OptionChain> chains, LocalDate date, int minDays) {
        OptionChain best = null;
        for (OptionChain chain : chains) {
            if (java.time.temporal.ChronoUnit.DAYS.between(date, chain.expiry()) >= minDays
                    && (best == null || chain.expiry().isBefore(best.expiry()))) {
                best = chain;
            }
        }
        return best;
    }

    private static double tradePnl(boolean isShort, double entry, double exit, int qty, double fees) {
        double perShare = isShort ? entry - exit : exit - entry;
        return perShare * CONTRACT_MULTIPLIER * qty - fees;
    }

    private static OptionalDouble findAtmStrike(OptionChain chain) {
        double spot = chain.spot();
        OptionalDouble best = OptionalDouble.empty();
        for (OptionQuote q : chain.quotes()) {
            if (best.isEmpty() || Math.abs(q.strike() - spot) < Math.abs(best.getAsDouble() - spot)) {
                best = OptionalDouble.of(q.strike());
            }
        }
        return best;
    }

    private static OptionQuote findQuote(OptionChain chain, double strike, OptionType type) {
        for (OptionQuote q : chain.quotes()) {
            if (q.type() == type && Math.abs(q.strike() - strike) < 1e-6) {
                return q;
            }
        }
        return null;
    }
}
