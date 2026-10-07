package com.sbk.optionspricer.volatility;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where the dashboard gets its fitted volatility surface from. Implemented by the calibration service; the
 * dashboard shows the status while nothing is ready and labels every surface with its provenance.
 */
public interface VolatilitySurfaceSource {

    enum State { LOADING, READY, FAILED }

    record Status(State state, String message, Instant updatedAt) {}

    /**
     * @param source     the provider(s) the chains came from, e.g. {@code YAHOO_FINANCE} or {@code SYNTHETIC}
     * @param marketData false when any chain is a synthetic fallback: the surface is then a demonstration
     * @param ssvi       the SSVI fit, or null if it could not be fitted (see warnings)
     * @param sabr       the SABR fit, or null if it could not be fitted
     * @param svi        the raw SVI (per expiry) fit, or null if it could not be fitted
     */
    record Snapshot(Instant asOf, String symbol, String source, boolean marketData, double spot,
                    SurfaceFitter.Fit ssvi, SurfaceFitter.Fit sabr, SurfaceFitter.Fit svi, int quotesSkipped, List<String> warnings) {}

    Optional<Snapshot> latest();

    Status status();
}
