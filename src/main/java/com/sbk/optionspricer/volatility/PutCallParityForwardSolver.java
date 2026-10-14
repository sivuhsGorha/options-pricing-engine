package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the market's forward for one expiry from put-call parity. For a European pair at strike K,
 * {@code C - P = D * (F - K)}, so each near-the-money pair gives {@code F = K + (C - P) / D}; the forward is the
 * median over the pairs, and the dividend yield is whatever carry that forward implies.
 *
 * <p>Two choices are deliberate. The discount factor {@code D} comes from the rate curve and is not estimated, because
 * the options are American: a put well in the money is worth more than parity says, and a regression of {@code C - P}
 * on strike across the chain reads that premium as a different discount factor (several times too large a rate error
 * for SPY at one month). And only strikes within {@link #NEAR_MONEY} of spot are used, where early exercise is worth
 * very little, with a median so that a few bad quotes do not move the answer. If the pairs disagree about the forward,
 * or it implies an implausible carry, the assumed rate and dividend are returned and {@code isMarketImplied} is false.
 */
public final class PutCallParityForwardSolver {

    /** Pairs are used only within this fraction of spot. */
    static final double NEAR_MONEY = 0.05;
    /** The median absolute deviation of the pairs' forwards, as a fraction of spot, above which they do not agree. */
    static final double MAX_DISPERSION = 0.002;
    /** The implied carry (r - q) may differ from the assumed one by at most this much (an annualised fraction). */
    static final double MAX_CARRY_DEVIATION = 0.10;
    private static final int MIN_PAIRS = 3;

    /**
     * @param discountFactor the factor used to read the forward: exp(-r T) with the assumed rate
     * @param riskFreeRate   the assumed rate, unchanged: the options do not identify it
     * @param dividendYield  the yield that makes the assumed rate reproduce the forward (the assumed one when not implied)
     * @param dispersion     median absolute deviation of the pairs' forwards, as a fraction of spot; 0 when not computed
     */
    public record ParityResult(
            double forward,
            double discountFactor,
            double riskFreeRate,
            double dividendYield,
            double dispersion,
            int pairedQuotesCount,
            boolean isMarketImplied
    ) {}

    private PutCallParityForwardSolver() {}

    public static ParityResult solve(OptionChain chain, double defaultRate, double defaultDividend, LocalDate asOf) {
        if (chain == null || asOf == null) {
            throw new IllegalArgumentException("chain and asOf must not be null");
        }
        double t = TimeConventions.yearFraction(asOf, chain.expiry());
        double spot = chain.spot();
        if (t <= 1e-6) {
            return new ParityResult(spot, 1.0, defaultRate, defaultDividend, 0.0, 0, false);
        }
        double discount = Math.exp(-defaultRate * t);

        Map<Double, OptionQuote> calls = new HashMap<>();
        Map<Double, OptionQuote> puts = new HashMap<>();
        for (OptionQuote q : chain.quotes()) {
            if (q.bid() > 0.0 && q.ask() >= q.bid()) {
                (q.type() == OptionType.CALL ? calls : puts).put(q.strike(), q);
            }
        }

        List<Double> forwards = new ArrayList<>();
        for (Map.Entry<Double, OptionQuote> entry : calls.entrySet()) {
            double k = entry.getKey();
            OptionQuote put = puts.get(k);
            if (put != null && Math.abs(k - spot) / spot <= NEAR_MONEY) {
                forwards.add(k + (entry.getValue().midPrice() - put.midPrice()) / discount);
            }
        }
        int n = forwards.size();
        if (n < MIN_PAIRS) {
            return assumed(spot, t, defaultRate, defaultDividend, 0.0, n);
        }

        Collections.sort(forwards);
        double forward = median(forwards);
        List<Double> deviations = new ArrayList<>(n);
        for (double f : forwards) {
            deviations.add(Math.abs(f - forward));
        }
        Collections.sort(deviations);
        double dispersion = median(deviations) / spot;

        double carry = forward > 0.0 ? Math.log(forward / spot) / t : Double.NaN;
        double assumedCarry = defaultRate - defaultDividend;
        if (!(forward > 0.0) || dispersion > MAX_DISPERSION || !(Math.abs(carry - assumedCarry) <= MAX_CARRY_DEVIATION)) {
            return assumed(spot, t, defaultRate, defaultDividend, dispersion, n);
        }
        return new ParityResult(forward, discount, defaultRate, defaultRate - carry, dispersion, n, true);
    }

    private static ParityResult assumed(double spot, double t, double rate, double dividend, double dispersion, int pairs) {
        return new ParityResult(spot * Math.exp((rate - dividend) * t), Math.exp(-rate * t), rate, dividend, dispersion, pairs, false);
    }

    /** Median of an already sorted, non-empty list. */
    private static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : 0.5 * (sorted.get(n / 2 - 1) + sorted.get(n / 2));
    }
}
