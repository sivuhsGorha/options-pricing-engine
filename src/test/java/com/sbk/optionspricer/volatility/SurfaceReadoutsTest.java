package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Skew and term structure read off the fitted surface, reported beside each decision and never traded on. */
class SurfaceReadoutsTest {

    private static final double DAY = 1.0 / 365.0;

    /** Two fitted expiries (30 and 90 days); vols per strike for 80..120 in steps of 10: a put-side skew, and a rising term structure. */
    private static SurfaceFitter.Fit fit() {
        double[] strikes = {80, 90, 100, 110, 120};
        double[] expiries = {30 * DAY, 90 * DAY};
        double[][] vols = {
                {0.30, 0.24, 0.20, 0.18, 0.17},   // 30d: ATM 20%
                {0.32, 0.27, 0.23, 0.21, 0.20}};  // 90d: ATM 23%
        return new SurfaceFitter.Fit("SSVI", strikes, expiries, vols, List.of(), 0.002, 40, Map.of(), true, List.of(), expiries);
    }

    @Test
    void theSkewIsTheLowWingMinusTheHighWingAndTheTermSlopeIsTheLaterExpiryMinusTheFront() {
        Optional<SurfaceReadouts.Readout> readout = SurfaceReadouts.of(fit(), 30 * DAY, 100.0);

        assertTrue(readout.isPresent());
        // 95% of spot = 95 is halfway between 24% and 20% = 22%; 105 is halfway between 20% and 18% = 19%
        assertEquals(0.03, readout.get().skew(), 1e-9);
        // the 90-day slice at the money (100) is 23%, the front 20%
        assertEquals(0.03, readout.get().termSlope(), 1e-9);
        assertEquals(60, readout.get().termDays());
        assertTrue(readout.get().text().contains("skew +3.0 pts") && readout.get().text().contains("term +3.0 pts over 60d"), readout.get().text());
    }

    @Test
    void aFlatSurfaceReadsZeroAndAnInvertedTermStructureReadsNegative() {
        double[] strikes = {80, 100, 120};
        double[] expiries = {30 * DAY, 90 * DAY};
        double[][] vols = {{0.25, 0.25, 0.25}, {0.20, 0.20, 0.20}};
        SurfaceFitter.Fit flat = new SurfaceFitter.Fit("SSVI", strikes, expiries, vols, List.of(), 0.0, 10, Map.of(), true, List.of(), expiries);

        SurfaceReadouts.Readout readout = SurfaceReadouts.of(flat, 30 * DAY, 100.0).orElseThrow();

        assertEquals(0.0, readout.skew(), 1e-12);
        assertEquals(-0.05, readout.termSlope(), 1e-9, "front-month stress: later expiries are cheaper");
        assertTrue(readout.text().contains("term -5.0 pts"), readout.text());
    }

    @Test
    void withOneFittedExpiryOnlyTheSkewIsReportedAndWithNoneNothingIs() {
        double[] strikes = {90, 100, 110};
        double[] expiries = {30 * DAY};
        SurfaceFitter.Fit single = new SurfaceFitter.Fit("SSVI", strikes, expiries, new double[][] {{0.24, 0.20, 0.18}}, List.of(), 0.0, 5, Map.of(), true,
                List.of(), expiries);

        SurfaceReadouts.Readout readout = SurfaceReadouts.of(single, 30 * DAY, 100.0).orElseThrow();

        assertTrue(Double.isNaN(readout.termSlope()));
        assertTrue(readout.text().contains("skew") && !readout.text().contains("term"), readout.text());
        assertTrue(SurfaceReadouts.of(null, 30 * DAY, 100.0).isEmpty());
        assertTrue(SurfaceReadouts.of(fit(), 30 * DAY, Double.NaN).isEmpty(), "no spot, no readout");
        assertTrue(SurfaceReadouts.of(fit(), 200 * DAY, 100.0).isEmpty(), "no fitted slice near the requested expiry");
    }
}
