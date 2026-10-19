package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The walk-forward comparison uses only what was known at each point, and counts and scores honestly. */
class ForecastEvaluatorTest {

    private static final double ANNUALISE = Math.sqrt(252.0);

    /** Closes whose daily log returns are exactly the given ones. */
    private static double[] closesFor(double[] returns) {
        double[] closes = new double[returns.length + 1];
        closes[0] = 100.0;
        for (int i = 0; i < returns.length; i++) {
            closes[i + 1] = closes[i] * Math.exp(returns[i]);
        }
        return closes;
    }

    private static double[] alternating(int n, double size) {
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = (i % 2 == 0 ? 1 : -1) * size;
        return r;
    }

    private static ForecastEvaluator.Score score(ForecastEvaluator.Result r, String name) {
        return r.scores().stream().filter(s -> s.model().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void whenEveryDayMovesTheSameAmountTheTrailingAndEwmaForecastsAreExactAndTheDefaultHarIsNot() {
        double[] returns = alternating(150, 0.02);

        ForecastEvaluator.Result r = ForecastEvaluator.evaluate(closesFor(returns), 5, 66);

        assertEquals(0.02 * ANNUALISE, r.meanRealized(), 1e-9);
        assertEquals(0.0, score(r, ForecastEvaluator.TRAILING).rmse(), 1e-9);
        assertEquals(0.0, score(r, ForecastEvaluator.EWMA).rmse(), 1e-9);
        assertEquals(0.0, score(r, ForecastEvaluator.TRAILING).qlike(), 1e-9);
        // the illustrative HAR weights add a constant and shrink the rest: they miss, and the score must show it
        assertTrue(Math.abs(score(r, ForecastEvaluator.HAR_DEFAULT).bias()) > 0.005, "default HAR bias " + score(r, ForecastEvaluator.HAR_DEFAULT).bias());
    }

    @Test
    void itCountsOneEvaluationPerForecastOriginAndNeverScoresOnDaysItCouldNotSee() {
        // 100 calm returns, then 40 turbulent ones
        double[] returns = new double[140];
        for (int i = 0; i < returns.length; i++) returns[i] = (i % 2 == 0 ? 1 : -1) * (i < 100 ? 0.005 : 0.03);

        ForecastEvaluator.Result r = ForecastEvaluator.evaluate(closesFor(returns), 5, 66);

        assertEquals(returns.length - 5 - 66 + 1, r.evaluations(), "origins from the 66th return to the last that has 5 days after it");
        assertEquals(5, r.horizon());
        // the trailing window lags a volatility jump, so across the jump it forecasts low: negative bias, never zero
        assertTrue(score(r, ForecastEvaluator.TRAILING).bias() < 0.0, "bias " + score(r, ForecastEvaluator.TRAILING).bias());
    }

    @Test
    void aFittedHarIsRefitOnlyOnReturnsKnownAtEachOriginAndScoresAreFinite() {
        Random random = new Random(7);
        double[] returns = new double[260];
        for (int i = 0; i < returns.length; i++) returns[i] = 0.01 * random.nextGaussian();

        ForecastEvaluator.Result r = ForecastEvaluator.evaluate(closesFor(returns), 21, 66);

        assertEquals(4, r.scores().size());
        for (ForecastEvaluator.Score s : r.scores()) {
            assertTrue(Double.isFinite(s.rmse()) && Double.isFinite(s.bias()) && Double.isFinite(s.qlike()), s.toString());
            assertEquals(r.evaluations(), s.n());
        }
        assertTrue(r.fittedShare() > 0.9, "the fit should have run at nearly every origin: " + r.fittedShare());
    }

    @Test
    void tooFewClosesIsRefusedRatherThanScoredOnNothing() {
        assertThrows(IllegalArgumentException.class, () -> ForecastEvaluator.evaluate(closesFor(alternating(60, 0.01)), 5, 66));
        assertThrows(IllegalArgumentException.class, () -> ForecastEvaluator.evaluate(null, 5, 66));
        assertThrows(IllegalArgumentException.class, () -> ForecastEvaluator.evaluate(closesFor(alternating(150, 0.01)), 0, 66));
        assertThrows(IllegalArgumentException.class, () -> ForecastEvaluator.evaluate(new double[]{100, -1, 100}, 1, 1));
    }
}
