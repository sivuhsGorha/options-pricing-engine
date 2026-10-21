package com.sbk.optionspricer.volatility;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Skew and term structure read off the fitted surface, reported beside each decision as evidence and never traded on: nothing in this engine has
 * shown that either predicts anything, and a signal that trades before it has shown that is a guess with a position behind it.
 *
 * <p><b>Skew</b> is the implied volatility at 95% of spot minus that at 105% on the requested expiry, in volatility points: positive when downside
 * protection is dearer than upside, as it usually is for an index. <b>Term slope</b> is the at-the-money volatility of a later fitted expiry minus
 * that of the requested one: positive in calm markets, negative when the front is stressed. The later expiry is the fitted one nearest 30 days
 * beyond the requested expiry (and at least a week beyond it); with none, the slope is NaN and left out.
 */
public final class SurfaceReadouts {

    private static final double DAYS = 365.0;
    private static final double MATCH_DAYS = 3.0;

    private SurfaceReadouts() {}

    /** @param skew vol points as a fraction (0.03 is 3 points); @param termSlope NaN when no later expiry was fitted */
    public record Readout(double skew, double termSlope, long termDays) {
        /** " | skew +3.0 pts, term +3.0 pts over 60d" for a decision's reason. */
        public String text() {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "skew %+.1f pts", 100 * skew));
            if (Double.isFinite(termSlope)) {
                sb.append(String.format(Locale.ROOT, ", term %+.1f pts over %dd", 100 * termSlope, termDays));
            }
            return sb.toString();
        }
    }

    /** Empty when the fit, the spot or a fitted slice near {@code t} (years) is missing. */
    public static Optional<Readout> of(SurfaceFitter.Fit fit, double t, double spot) {
        if (fit == null || fit.fittedExpiries() == null || !(spot > 0.0) || !Double.isFinite(t)) {
            return Optional.empty();
        }
        OptionalDouble low = SurfaceFitter.volAt(fit, t, 0.95 * spot, MATCH_DAYS);
        OptionalDouble high = SurfaceFitter.volAt(fit, t, 1.05 * spot, MATCH_DAYS);
        OptionalDouble front = SurfaceFitter.volAt(fit, t, spot, MATCH_DAYS);
        if (low.isEmpty() || high.isEmpty() || front.isEmpty()) {
            return Optional.empty();
        }

        double later = Double.NaN;
        double target = t + 30.0 / DAYS;
        for (double expiry : fit.fittedExpiries()) {
            if (expiry >= t + 7.0 / DAYS && (Double.isNaN(later) || Math.abs(expiry - target) < Math.abs(later - target))) {
                later = expiry;
            }
        }
        double slope = Double.NaN;
        long days = 0;
        if (Double.isFinite(later)) {
            OptionalDouble laterVol = SurfaceFitter.volAt(fit, later, spot, 0.5);
            if (laterVol.isPresent()) {
                slope = laterVol.getAsDouble() - front.getAsDouble();
                days = Math.round((later - t) * DAYS);
            }
        }
        return Optional.of(new Readout(low.getAsDouble() - high.getAsDouble(), slope, days));
    }
}
