package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;
import com.sbk.optionspricer.volatility.RealizedVolForecast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Replays recorded chain snapshots against a realized-volatility forecast: sell the front-expiry at-the-money
 * straddle when implied vol is rich to the forecast, buy it when cheap, close it when the gap has reverted.
 *
 * <p>What makes the figures honest, and what does not. Fills are at the touch: a short is opened at the bids and
 * closed at the asks (and the reverse for a long), and a fee is charged per contract on every leg and every trade, so a
 * signal that never pays loses its spread. Trades are dated from the snapshots, not from the clock. The forecaster must
 * already hold one close per day (see {@link RealizedVolForecast}); it is not fed from the snapshots, which arrive
 * every minute. Not modelled: slippage beyond the touch, partial fills, assignment, and any hedge of the delta, so a
 * result here says whether the premium paid for the spread, not what the live strategy would have earned.
 */
public final class VrpSignalBacktester {

    /** One recorded chain and the moment it describes. */
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
            boolean enforceCostHurdle
    ) {
        public static ReplayConfig defaults() {
            return new ReplayConfig(0.01, 1, 0.65, false, 0, false);
        }
    }

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
            double meanVrp
    ) {
        public VrpBacktestResult(int totalSnapshots, int totalTrades, int winningTrades, double winRate,
                                 double totalPnl, double averagePnlPerTrade, double maxDrawdown, List<VrpTrade> trades) {
            this(totalSnapshots, totalTrades, winningTrades, winRate, totalPnl, averagePnlPerTrade, maxDrawdown, trades, totalTrades, 0, 0.0);
        }
    }

    private static final double CONTRACT_MULTIPLIER = 100.0;

    private VrpSignalBacktester() {}

    /**
     * @param snapshots       recorded chains in time order
     * @param volForecast     realized-vol forecaster already loaded with daily closes
     * @param vrpThreshold    minimum |implied - forecast| (a vol fraction, 0.02 for two points) to open
     * @param contractQty     straddles per trade
     * @param feePerContract  dollars per contract per leg per trade
     */
    public static VrpBacktestResult run(
            List<Snapshot> snapshots,
            RealizedVolForecast volForecast,
            double vrpThreshold,
            int contractQty,
            double feePerContract
    ) {
        return run(snapshots, volForecast, new ReplayConfig(vrpThreshold, contractQty, feePerContract, false, 0, false));
    }

    public static VrpBacktestResult run(
            List<Snapshot> snapshots,
            RealizedVolForecast volForecast,
            ReplayConfig config
    ) {
        if (snapshots == null || snapshots.isEmpty() || volForecast == null || config == null) {
            return new VrpBacktestResult(0, 0, 0, 0.0, 0.0, 0.0, 0.0, List.of(), 0, 0, 0.0);
        }

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
        Snapshot last = null;

        List<Double> vrpHistory = new ArrayList<>();
        double totalVrpSum = 0.0;
        int validVrpCount = 0;
        int hurdlesPassed = 0;
        int hurdlesRejected = 0;

        for (Snapshot snapshot : snapshots) {
            last = snapshot;
            OptionChain chain = snapshot.chain();
            LocalDate date = LocalDate.ofInstant(snapshot.at(), ZoneOffset.UTC);
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
            OptionalDouble fair = volForecast.fairVol(chain.symbol(), chain.expiry(), strike, t);
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
                double baseline = count > 0 ? winSum / count : 0.0;
                effectiveVrp = rawVrp - baseline;
            }

            // Cost-aware break-even hurdle: (half-spread + fees) / vega
            double callHalf = Math.max(0.0, (call.ask() - call.bid()) / 2.0);
            double putHalf = Math.max(0.0, (put.ask() - put.bid()) / 2.0);
            double roundTripFriction = 2.0 * (callHalf + putHalf + (2.0 * config.feePerContract() / 100.0));
            double callVega = com.sbk.optionspricer.BlackScholesPricer.vega(chain.spot(), strike, t, 0.05, marketAtmIv, 0.0);
            double straddleVega = 2.0 * callVega;
            double breakEvenHurdle = straddleVega > 1e-6 ? (roundTripFriction / straddleVega) : 0.05;

            if (openPrice != null) {
                boolean exitShort = openIsShort && (effectiveVrp <= 0.0 || effectiveVrp < openVrp * 0.5);
                boolean exitLong = !openIsShort && (effectiveVrp >= 0.0 || effectiveVrp > openVrp * 0.5);
                if (exitShort || exitLong) {
                    // closing a short buys it back at the asks; closing a long sells it at the bids
                    double exitPrice = openIsShort ? call.ask() + put.ask() : call.bid() + put.bid();
                    double tradePnl = tradePnl(openIsShort, openPrice, exitPrice, config.contractQty(), fees);
                    runningPnl += tradePnl;
                    peakPnl = Math.max(peakPnl, runningPnl);
                    maxDrawdown = Math.max(maxDrawdown, peakPnl - runningPnl);
                    executedTrades.add(new VrpTrade(date, openExpiry, openStrike, openIsShort, openPrice, exitPrice, openVrp, tradePnl));
                    openPrice = null;
                }
            } else {
                double hurdle = config.enforceCostHurdle() ? Math.max(config.vrpThreshold(), breakEvenHurdle) : config.vrpThreshold();
                if (effectiveVrp > hurdle) {
                    hurdlesPassed++;
                    openPrice = call.bid() + put.bid(); // implied vol rich: sell the straddle at the bids
                    openIsShort = true;
                    openVrp = effectiveVrp;
                    openExpiry = chain.expiry();
                    openStrike = strike;
                } else if (effectiveVrp < -hurdle) {
                    hurdlesPassed++;
                    openPrice = call.ask() + put.ask(); // implied vol cheap: buy it at the asks
                    openIsShort = false;
                    openVrp = effectiveVrp;
                    openExpiry = chain.expiry();
                    openStrike = strike;
                } else if (Math.abs(effectiveVrp) > config.vrpThreshold() && config.enforceCostHurdle()) {
                    hurdlesRejected++;
                }
            }
        }

        // a position still open at the end is closed at the last snapshot, at the touch like any other exit
        if (openPrice != null && last != null) {
            OptionQuote call = findQuote(last.chain(), openStrike, OptionType.CALL);
            OptionQuote put = findQuote(last.chain(), openStrike, OptionType.PUT);
            double exitPrice = call != null && put != null
                    ? (openIsShort ? call.ask() + put.ask() : call.bid() + put.bid())
                    : openPrice;
            double tradePnl = tradePnl(openIsShort, openPrice, exitPrice, config.contractQty(), fees);
            runningPnl += tradePnl;
            peakPnl = Math.max(peakPnl, runningPnl);
            maxDrawdown = Math.max(maxDrawdown, peakPnl - runningPnl);
            executedTrades.add(new VrpTrade(LocalDate.ofInstant(last.at(), ZoneOffset.UTC), openExpiry, openStrike, openIsShort,
                    openPrice, exitPrice, openVrp, tradePnl));
        }

        int total = executedTrades.size();
        int winners = (int) executedTrades.stream().filter(t -> t.pnl() > 0.0).count();
        double winRate = total > 0 ? (double) winners / total : 0.0;
        double avgPnl = total > 0 ? runningPnl / total : 0.0;
        double meanVrp = validVrpCount > 0 ? totalVrpSum / validVrpCount : 0.0;

        return new VrpBacktestResult(snapshots.size(), total, winners, winRate, runningPnl, avgPnl, maxDrawdown,
                executedTrades, hurdlesPassed, hurdlesRejected, meanVrp);
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
