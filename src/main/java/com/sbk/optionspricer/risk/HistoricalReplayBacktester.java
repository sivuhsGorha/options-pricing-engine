package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.data.OptionSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple historical replay engine that turns stored option snapshots into a
 * backtest-friendly sequence of trade signals and P&L metrics.
 */
public class HistoricalReplayBacktester {

    public record ReplayResult(
            int snapshotsProcessed,
            int tradeCount,
            double totalPnL,
            double averageSlippage,
            double maxDrawdown
    ) {}

    public static ReplayResult runReplay(List<OptionSnapshot> snapshots, int tradeSize, double thresholdPct) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (tradeSize <= 0) {
            throw new IllegalArgumentException("tradeSize must be positive");
        }
        if (!Double.isFinite(thresholdPct) || thresholdPct < 0.0) {
            throw new IllegalArgumentException("thresholdPct must be finite and non-negative");
        }

        List<OptionSnapshot> ordered = new ArrayList<>(snapshots);
        if (ordered.isEmpty()) {
            return new ReplayResult(0, 0, 0.0, 0.0, 0.0);
        }

        Collections.sort(ordered, java.util.Comparator.comparing(OptionSnapshot::timestamp));

        Double entryPrice = null;
        double lastPrice = ordered.getFirst().midPrice();
        double currentPnL = 0.0;
        double totalSlippage = 0.0;
        // Largest fall of cumulative P&L from its running peak (the start counts as a peak of zero), never negative.
        // It was the lowest cumulative P&L, so a run that made 20 and gave it all back reported no drawdown.
        double peakPnL = 0.0;
        double maxDrawdown = 0.0;
        int trades = 0;

        for (int i = 1; i < ordered.size(); i++) {
            OptionSnapshot current = ordered.get(i);
            double currentMid = current.midPrice();
            double relativeMove = Math.abs(currentMid - lastPrice) / Math.max(Math.abs(lastPrice), 1.0);
            double threshold = thresholdPct / 100.0;

            if (entryPrice == null) {
                if (relativeMove >= threshold) {
                    entryPrice = currentMid;
                    trades++;
                }
            } else {
                double moveFromEntry = Math.abs(currentMid - entryPrice) / Math.max(Math.abs(entryPrice), 1.0);
                if (moveFromEntry >= threshold) {
                    currentPnL += (currentMid - entryPrice) * tradeSize;
                    totalSlippage += Math.abs(currentMid - entryPrice) * 0.25;
                    peakPnL = Math.max(peakPnL, currentPnL);
                    maxDrawdown = Math.max(maxDrawdown, peakPnL - currentPnL);
                    entryPrice = null;
                }
            }

            lastPrice = currentMid;
        }

        if (entryPrice != null) {
            currentPnL += (lastPrice - entryPrice) * tradeSize;
            totalSlippage += Math.abs(lastPrice - entryPrice) * 0.25;
            // Not counted again: the entry already counted this trade, whether it closes in the loop or at the end.
            peakPnL = Math.max(peakPnL, currentPnL);
            maxDrawdown = Math.max(maxDrawdown, peakPnL - currentPnL);
        }

        double averageSlippage = trades == 0 ? 0.0 : totalSlippage / trades;
        return new ReplayResult(ordered.size(), trades, currentPnL, averageSlippage, maxDrawdown);
    }
}
