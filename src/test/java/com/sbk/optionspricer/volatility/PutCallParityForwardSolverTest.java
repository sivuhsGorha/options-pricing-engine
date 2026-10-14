package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PutCallParityForwardSolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 20); // ~37 days
    private static final double SPOT = 100.0;
    private static final double TRUE_RATE = 0.045; // 4.5%
    private static final double TRUE_DIVIDEND = 0.015; // 1.5%

    private static List<OptionQuote> parityQuotes(double[] strikes, double halfSpread, double itmPutPremium) {
        double t = TimeConventions.yearFraction(TODAY, EXPIRY);
        List<OptionQuote> quotes = new ArrayList<>();
        for (double k : strikes) {
            double callMid = com.sbk.optionspricer.BlackScholesPricer.price(OptionType.CALL, SPOT, k, t, TRUE_RATE, 0.20, TRUE_DIVIDEND);
            double putMid = com.sbk.optionspricer.BlackScholesPricer.price(OptionType.PUT, SPOT, k, t, TRUE_RATE, 0.20, TRUE_DIVIDEND);
            if (k > SPOT * 1.02) {
                putMid += itmPutPremium; // an American put in the money is worth more than its European value
            }
            // a far out-of-the-money quote can have no bid at all; the solver ignores a pair without one
            quotes.add(new OptionQuote("SPY", EXPIRY, k, OptionType.CALL, Math.max(0.0, callMid - halfSpread), callMid + halfSpread, 0.20, 100L, 1000L));
            quotes.add(new OptionQuote("SPY", EXPIRY, k, OptionType.PUT, Math.max(0.0, putMid - halfSpread), putMid + halfSpread, 0.20, 100L, 1000L));
        }
        return quotes;
    }

    @Test
    void recoversTheMarketImpliedForwardAndTheDividendYieldItImplies() {
        double t = TimeConventions.yearFraction(TODAY, EXPIRY);
        double trueForward = SPOT * Math.exp((TRUE_RATE - TRUE_DIVIDEND) * t);
        double[] strikes = {92.0, 95.0, 98.0, 100.0, 102.0, 105.0, 108.0};
        OptionChain chain = new OptionChain("SPY", EXPIRY, SPOT, parityQuotes(strikes, 0.02, 0.0));

        // the rate curve is the trusted input here; only the forward (and so the carry) is read from the options
        PutCallParityForwardSolver.ParityResult result = PutCallParityForwardSolver.solve(chain, TRUE_RATE, 0.0, TODAY);

        assertTrue(result.isMarketImplied(), "Should successfully extract market implied forward");
        assertEquals(trueForward, result.forward(), 1e-3, "forward from the near-the-money pairs");
        assertEquals(Math.exp(-TRUE_RATE * t), result.discountFactor(), 1e-12, "the discount factor comes from the rate curve, not from the options");
        assertEquals(TRUE_RATE, result.riskFreeRate(), 1e-12, "the rate is not estimated");
        assertEquals(TRUE_DIVIDEND, result.dividendYield(), 2e-3, "dividend yield implied by the forward");
        assertTrue(result.dispersion() < 1e-3, "the pairs agree on the forward: " + result.dispersion());
        assertEquals(5, result.pairedQuotesCount(), "only the strikes within 5% of spot are used");
    }

    @Test
    void anAmericanPutPremiumFarInTheMoneyDoesNotMoveTheForward() {
        // Deep in-the-money puts carry an early-exercise premium that put-call parity does not know about. A fit across
        // the whole chain reads it as a change in the discount factor; the near-the-money median does not see it.
        double t = TimeConventions.yearFraction(TODAY, EXPIRY);
        double trueForward = SPOT * Math.exp((TRUE_RATE - TRUE_DIVIDEND) * t);
        double[] strikes = {85.0, 88.0, 90.0, 93.0, 96.0, 98.0, 100.0, 102.0, 104.0, 107.0, 110.0, 113.0, 115.0};
        OptionChain chain = new OptionChain("SPY", EXPIRY, SPOT, parityQuotes(strikes, 0.02, 1.50));

        PutCallParityForwardSolver.ParityResult result = PutCallParityForwardSolver.solve(chain, TRUE_RATE, 0.0, TODAY);

        assertTrue(result.isMarketImplied());
        assertEquals(trueForward, result.forward(), 0.02, "forward stays within two cents despite a $1.50 premium on the far puts");
    }

    @Test
    void fallsBackToTheAssumedCarryWhenTheQuotesDisagreeAboutTheForward() {
        double[] strikes = {99.0, 100.0, 101.0, 102.0, 103.0};
        List<OptionQuote> quotes = new ArrayList<>(parityQuotes(strikes, 0.02, 0.0));
        // the puts are off by -2, -1, 0, +1, +2 dollars: every pair names a different forward, none agrees with the rest
        double[] offsets = {+2.0, +1.0, 0.0, -1.0, -2.0};
        for (int i = 0; i < strikes.length; i++) {
            final double strike = strikes[i];
            final double offset = offsets[i];
            quotes.replaceAll(q -> q.type() == OptionType.PUT && q.strike() == strike
                        ? new OptionQuote("SPY", EXPIRY, strike, OptionType.PUT, q.bid() + offset, q.ask() + offset, 0.2, 100L, 1000L) : q);
        }
        OptionChain chain = new OptionChain("SPY", EXPIRY, SPOT, quotes);

        PutCallParityForwardSolver.ParityResult result = PutCallParityForwardSolver.solve(chain, 0.05, 0.02, TODAY);

        assertFalse(result.isMarketImplied(), "scattered pairs: fall back");
        double t = TimeConventions.yearFraction(TODAY, EXPIRY);
        assertEquals(SPOT * Math.exp((0.05 - 0.02) * t), result.forward(), 1e-9);
        assertEquals(0.02, result.dividendYield(), 1e-12);
    }

    @Test
    void fallsBackSafelyWhenQuotesAreSparseOrDegenerate() {
        List<OptionQuote> quotes = List.of(
                new OptionQuote("SPY", EXPIRY, 100.0, OptionType.CALL, 2.0, 2.1, 0.20, 100L, 1000L)
        );
        OptionChain chain = new OptionChain("SPY", EXPIRY, SPOT, quotes);
        PutCallParityForwardSolver.ParityResult result = PutCallParityForwardSolver.solve(chain, 0.05, 0.02, TODAY);

        assertFalse(result.isMarketImplied(), "Should fall back gracefully when fewer than 3 pairs exist");
        double t = TimeConventions.yearFraction(TODAY, EXPIRY);
        double expectedFallbackForward = SPOT * Math.exp((0.05 - 0.02) * t);
        assertEquals(expectedFallbackForward, result.forward(), 1e-6);
        assertEquals(Math.exp(-0.05 * t), result.discountFactor(), 1e-6);
    }
}
