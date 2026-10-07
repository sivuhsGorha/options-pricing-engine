package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.TimeConventions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** The fitter recovers a known surface from prices, skips what it cannot invert, and never emits NaN. */
class SurfaceFitterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 7);
    private static final double SPOT = 100.0, R = 0.03, Q = 0.01;

    /** A chain whose every quote is a two-sided market at the Black-Scholes price for vol(T, K). */
    private static OptionChain chainFrom(LocalDate expiry, double[] strikes, DoubleBinaryOperator volOf) {
        double t = TimeConventions.yearFraction(AS_OF, expiry);
        List<OptionQuote> quotes = new ArrayList<>();
        for (double k : strikes) {
            double vol = volOf.applyAsDouble(t, k);
            double call = BlackScholesPricer.price(OptionType.CALL, SPOT, k, t, R, vol, Q);
            double put = BlackScholesPricer.price(OptionType.PUT, SPOT, k, t, R, vol, Q);
            quotes.add(new OptionQuote("SPY", expiry, k, OptionType.CALL, call, call, vol, 100, 100));
            quotes.add(new OptionQuote("SPY", expiry, k, OptionType.PUT, put, put, vol, 100, 100));
        }
        return new OptionChain("SPY", expiry, SPOT, quotes);
    }

    private static double[] range(double from, double to, double step) {
        int n = (int) Math.round((to - from) / step) + 1;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = from + i * step;
        return out;
    }

    private static double forward(double t) {
        return SPOT * Math.exp((R - Q) * t);
    }

    private static void assertFiniteGrid(SurfaceFitter.Fit fit) {
        for (double[] row : fit.vols()) {
            for (double v : row) assertTrue(Double.isFinite(v) && v > 0.02 && v < 1.5, "grid vol " + v);
        }
        assertEquals(fit.expiries().length, fit.vols().length);
        assertEquals(fit.strikes().length, fit.vols()[0].length);
    }

    @Test
    void ssviRoundTripRecoversTheSurfaceItWasGeneratedFrom() {
        SsviApproximation.SsviParams truth = new SsviApproximation.SsviParams(0.6, 0.3, -0.5);
        DoubleBinaryOperator vol = (t, k) -> SsviApproximation.impliedVolFromForward(forward(t), k, t, 0.22 - 0.03 * t, truth);
        List<OptionChain> chains = new ArrayList<>();
        for (int days : new int[]{30, 60, 90, 180}) chains.add(chainFrom(AS_OF.plusDays(days), range(80, 120, 2.5), vol));

        SurfaceFitter.Extraction extraction = SurfaceFitter.extractPoints(chains, R, Q, AS_OF);
        SurfaceFitter.Fit fit = SurfaceFitter.fitSsvi(extraction.points());

        assertEquals(4, fit.expiries().length);
        assertTrue(fit.rmse() < 2e-3, "RMSE in vol: " + fit.rmse() + " warnings " + fit.warnings());
        assertEquals(-0.5, fit.parameters().get("rho"), 0.05);
        assertEquals(0.6, fit.parameters().get("eta"), 0.12);
        assertTrue(fit.noArbitrageConditionsHold());
        assertEquals(extraction.points().size(), fit.points().size());
        assertFiniteGrid(fit);
    }

    @Test
    void sabrRoundTripRecoversRhoAndNuPerExpiry() {
        double alpha = 2.0, rho = -0.4, nu = 0.6; // alpha / F^(1 - beta) ~ 0.2 at F ~ 100
        DoubleBinaryOperator vol = (t, k) -> SabrModel.impliedVolatility(forward(t), k, t, alpha, SurfaceFitter.SABR_BETA, rho, nu);
        List<OptionChain> chains = List.of(chainFrom(AS_OF.plusDays(90), range(85, 115, 2.5), vol));

        SurfaceFitter.Fit fit = SurfaceFitter.fitSabr(SurfaceFitter.extractPoints(chains, R, Q, AS_OF).points());

        assertTrue(fit.rmse() < 1e-3, "RMSE " + fit.rmse() + " warnings " + fit.warnings());
        double fittedRho = fit.parameters().entrySet().stream().filter(e -> e.getKey().startsWith("rho[")).findFirst().orElseThrow().getValue();
        double fittedNu = fit.parameters().entrySet().stream().filter(e -> e.getKey().startsWith("nu[")).findFirst().orElseThrow().getValue();
        assertEquals(rho, fittedRho, 0.05);
        assertEquals(nu, fittedNu, 0.1);
        assertEquals(SurfaceFitter.SABR_BETA, fit.parameters().get("beta"));
        assertFalse(fit.noArbitrageConditionsHold(), "Hagan SABR makes no such guarantee and must not claim one");
        assertFiniteGrid(fit);
    }

    @Test
    void quotesWithoutATwoSidedMarketAreSkippedAndCountedAndTheInTheMoneySideIsIgnored() {
        LocalDate expiry = AS_OF.plusDays(30);
        double t = TimeConventions.yearFraction(AS_OF, expiry);
        double put95 = BlackScholesPricer.price(OptionType.PUT, SPOT, 95, t, R, 0.2, Q);
        double call105 = BlackScholesPricer.price(OptionType.CALL, SPOT, 105, t, R, 0.2, Q);
        OptionChain chain = new OptionChain("SPY", expiry, SPOT, List.of(
                new OptionQuote("SPY", expiry, 90, OptionType.PUT, 0.0, 1.0, 0.2, 0, 0),          // no bid: skipped
                new OptionQuote("SPY", expiry, 95, OptionType.PUT, put95, put95, 0.2, 0, 0),      // used
                new OptionQuote("SPY", expiry, 90, OptionType.CALL, 11.0, 11.2, 0.2, 0, 0),       // in the money: ignored, not counted
                new OptionQuote("SPY", expiry, 105, OptionType.CALL, call105, call105, 0.2, 0, 0) // used
        ));

        SurfaceFitter.Extraction extraction = SurfaceFitter.extractPoints(List.of(chain), R, Q, AS_OF);

        assertEquals(2, extraction.points().size());
        assertEquals(1, extraction.quotesSkipped());
        assertTrue(extraction.points().stream().allMatch(p -> Math.abs(p.marketVol() - 0.2) < 1e-6), extraction.points().toString());
    }

    @Test
    void anExpiryWithTooFewQuotesIsExcludedWithAWarningAndNothingUsableIsAnError() {
        LocalDate near = AS_OF.plusDays(30), far = AS_OF.plusDays(90);
        List<SurfaceFitter.MarketPoint> points = new ArrayList<>();
        for (double k : new double[]{90, 95, 100, 105, 110}) points.add(new SurfaceFitter.MarketPoint(near, 30 / 365.0, k, 100.2, k < 100.2 ? OptionType.PUT : OptionType.CALL, 0.2 + 0.001 * Math.abs(k - 100)));
        points.add(new SurfaceFitter.MarketPoint(far, 90 / 365.0, 95, 100.5, OptionType.PUT, 0.21));
        points.add(new SurfaceFitter.MarketPoint(far, 90 / 365.0, 105, 100.5, OptionType.CALL, 0.19));

        SurfaceFitter.Fit fit = SurfaceFitter.fitSsvi(points);

        assertEquals(1, fit.expiries().length, "the two-quote expiry must be dropped, not fitted");
        assertTrue(fit.warnings().stream().anyMatch(w -> w.contains("excluded")), fit.warnings().toString());
        assertThrows(IllegalArgumentException.class, () -> SurfaceFitter.fitSsvi(List.of()));
        assertThrows(IllegalArgumentException.class, () -> SurfaceFitter.fitSabr(points.subList(5, 7)));
    }

    @Test
    void aFlatSyntheticChainFitsAFlatSurface() {
        LocalDate today = LocalDate.now();
        OptionChainProvider synthetic = new SyntheticOptionChainProvider(100.0, 0.20, 0.05, 0.0);
        List<OptionChain> chains = new ArrayList<>();
        for (LocalDate expiry : OptionChainProvider.thirdFridays(today, 3)) chains.add(synthetic.getOptionChain("SPY", expiry));

        SurfaceFitter.Extraction extraction = SurfaceFitter.extractPoints(chains, 0.05, 0.0, today);
        SurfaceFitter.Fit fit = SurfaceFitter.fitSsvi(extraction.points());

        // The 80 put of the nearest expiry prices at ~1e-10 and is correctly uninvertible; nothing else may be lost.
        assertTrue(extraction.quotesSkipped() <= 1, "skipped " + extraction.quotesSkipped());
        assertTrue(extraction.points().size() >= 20, "points " + extraction.points().size());
        assertTrue(fit.rmse() < 1e-3, "RMSE " + fit.rmse());
        for (double[] row : fit.vols()) for (double v : row) assertEquals(0.20, v, 0.005);
    }
}
