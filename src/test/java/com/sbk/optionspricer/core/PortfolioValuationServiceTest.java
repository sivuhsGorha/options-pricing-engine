package com.sbk.optionspricer.core;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.TimeConventions;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.Valuation;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The book is marked from real quotes and the fitted surface, every number carries its source, nothing is zero-filled. */
class PortfolioValuationServiceTest {

    private static MarketSnapshot spot(double last) {
        return new MarketSnapshot("SPY", last - 0.01, last + 0.01, last, 1000L, Instant.now(), Instant.now(), 0L, "TEST", MarketDataStatus.LIVE);
    }

    private static VolatilitySurfaceService calibratedSurface() {
        VolatilitySurfaceService surface = new VolatilitySurfaceService(new SyntheticOptionChainProvider(100.0, 0.20, 0.05, 0.0),
                "SPY", 0.05, 0.0, Duration.ofMinutes(15), Clock.systemUTC(), chain -> { });
        surface.refresh();
        return surface;
    }

    @Test
    void anOptionIsMarkedAtTheQuoteMidWithGreeksFromTheFittedSurfaceAndTheBookTotalsFollow() {
        VolatilitySurfaceService surface = calibratedSurface();
        LocalDate expiry = surface.loadedExpiries().get(0);
        String call = OccSymbol.format("SPY", expiry, OptionType.CALL, 100.0);
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        tracker.applyFill(new PositionTracker.ExecutionFill(call, 2, 100, 2.0));
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 1, 99.0));
        PortfolioValuationService service = new PortfolioValuationService(tracker, surface, surface, () -> spot(100.0), 0.05, 0.0, Duration.ofSeconds(5), Clock.systemUTC());

        Valuation v = service.revalue().orElseThrow();

        assertEquals(100.0, v.spot());
        assertEquals("TEST/LIVE", v.spotSource());
        Valuation.PositionValuation option = v.positions().stream().filter(p -> p.symbol().equals(call)).findFirst().orElseThrow();
        Valuation.PositionValuation stock = v.positions().stream().filter(p -> p.symbol().equals("SPY")).findFirst().orElseThrow();

        double t = TimeConventions.yearFraction(LocalDate.now(ZoneOffset.UTC), expiry);
        double mid = surface.optionQuote(call).orElseThrow().last();
        assertEquals("MID", option.markSource());
        assertEquals(mid, option.mark(), 1e-9);
        assertEquals("SVI", option.volSource());
        assertEquals(0.20, option.impliedVol(), 0.005, "the synthetic chain is flat 20%");
        double[] g = new double[5];
        BlackScholesPricer.greeks(OptionType.CALL, 100.0, 100.0, t, 0.05, option.impliedVol(), 0.0, g);
        assertEquals(g[0], option.delta(), 1e-9);
        assertEquals(g[1], option.gamma(), 1e-9);
        assertEquals(2 * 100 * (mid - 2.0), option.unrealizedPnl(), 1e-6);

        assertEquals("SPOT", stock.markSource());
        assertEquals(1.0, stock.delta());
        assertEquals(10 * (100.0 - 99.0), stock.unrealizedPnl(), 1e-9);

        assertEquals(option.unrealizedPnl() + stock.unrealizedPnl(), v.unrealizedPnl(), 1e-6);
        assertEquals(2 * 100 * g[0] + 10, v.netDelta(), 1e-6);
        assertEquals(2 * 100 * g[1], v.netGamma(), 1e-9);
        assertTrue(v.netTheta() < 0, "a long call bleeds theta");
        assertEquals(v.netDelta(), tracker.getNetDelta(), 1e-6, "the Greeks were pushed into the positions, so the tracker agrees");
        assertEquals(v.netGamma(), tracker.getNetGamma(), 1e-9);
        assertTrue(service.valuationStatus().isEmpty());
    }

    @Test
    void anExpiredContractIsValuedAtIntrinsicAndFlagged() {
        VolatilitySurfaceService surface = calibratedSurface();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        String oldCall = OccSymbol.format("SPY", LocalDate.of(2020, 1, 17), OptionType.CALL, 90.0);
        tracker.applyFill(new PositionTracker.ExecutionFill(oldCall, 1, 100, 5.0));
        PortfolioValuationService service = new PortfolioValuationService(tracker, surface, surface, () -> spot(100.0), 0.05, 0.0, Duration.ofSeconds(5), Clock.systemUTC());

        Valuation.PositionValuation row = service.revalue().orElseThrow().positions().get(0);

        assertTrue(row.expired());
        assertEquals("INTRINSIC", row.markSource());
        assertEquals(10.0, row.mark(), 1e-9);
        assertEquals(1.0, row.delta());
        assertEquals(0.0, row.gamma());
        assertEquals(100 * (10.0 - 5.0), row.unrealizedPnl(), 1e-9);
        assertTrue(row.note().contains("expired"));
    }

    @Test
    void withoutASpotNothingIsMarkedAndTheStatusSaysWhy() {
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        tracker.applyFill(new PositionTracker.ExecutionFill("SPY", 10, 1, 99.0));
        PortfolioValuationService service = new PortfolioValuationService(tracker, null, null, () -> null, 0.05, 0.0, Duration.ofSeconds(5), Clock.systemUTC());

        assertEquals(Optional.empty(), service.revalue());
        assertTrue(service.valuationStatus().contains("no spot"));
        assertTrue(service.latestValuation().isEmpty());
    }

    @Test
    void aContractWithNoQuoteAndNoMatchingSliceIsReportedAsUnvaluedNotAsZero() {
        VolatilitySurfaceService surface = calibratedSurface();
        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        String farCall = OccSymbol.format("SPY", LocalDate.now(ZoneOffset.UTC).plusYears(2), OptionType.CALL, 100.0);
        tracker.applyFill(new PositionTracker.ExecutionFill(farCall, 1, 100, 5.0));
        PortfolioValuationService service = new PortfolioValuationService(tracker, surface, surface, () -> spot(100.0), 0.05, 0.0, Duration.ofSeconds(5), Clock.systemUTC());

        Valuation v = service.revalue().orElseThrow();
        Valuation.PositionValuation row = v.positions().get(0);

        assertEquals("NONE", row.markSource());
        assertTrue(Double.isNaN(row.mark()));
        assertEquals("NONE", row.volSource());
        assertTrue(Double.isNaN(row.delta()));
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains(farCall)), v.warnings().toString());
        assertEquals(0.0, v.netDelta(), "an unvalued position contributes nothing rather than a made-up number");
    }

    @Test
    void theSurfaceLookupMatchesTheSliceByExpiryAndInterpolatesInStrike() {
        VolatilitySurfaceService surface = calibratedSurface();
        var fit = surface.latest().orElseThrow().svi();
        double t = fit.fittedExpiries()[1];

        assertEquals(0.20, PortfolioValuationService.surfaceVol(fit, t, 100.0).orElseThrow(), 0.005);
        assertEquals(0.20, PortfolioValuationService.surfaceVol(fit, t + 1.0 / 365, 97.3).orElseThrow(), 0.005, "a day off still matches the slice");
        assertTrue(PortfolioValuationService.surfaceVol(fit, t + 15.0 / 365, 100.0).isEmpty(), "halfway between two monthly slices matches nothing");
        assertTrue(PortfolioValuationService.surfaceVol(null, t, 100.0).isEmpty());
    }
}
