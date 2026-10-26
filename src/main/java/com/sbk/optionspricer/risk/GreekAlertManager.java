package com.sbk.optionspricer.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Monitors real-time Greek limits and manages alert generation and routing.
 */
public class GreekAlertManager {

    public enum AlertSeverity {
        WARNING, CRITICAL
    }

    public record GreekAlert(
            String metric,
            double value,
            double threshold,
            AlertSeverity severity,
            long timestamp
    ) {}

    private final double deltaThresholdWarning;
    private final double deltaThresholdCritical;
    private final double gammaThresholdWarning;
    private final double gammaThresholdCritical;
    private final double vegaThresholdWarning;
    private final double vegaThresholdCritical;
    /** Infinite when theta is aggregated but not limited. */
    private final double thetaThresholdWarning;
    private final double thetaThresholdCritical;
    /** Infinite when rho is aggregated but not limited. */
    private final double rhoThresholdWarning;
    private final double rhoThresholdCritical;

    /** Fraction of a threshold the value must fall below before an alert state clears. */
    private static final double HYSTERESIS = 0.90;

    private final List<Consumer<GreekAlert>> alertListeners = new ArrayList<>();
    private final java.util.Map<String, AlertSeverity> activeState = new java.util.HashMap<>();

    /** Delta, gamma and vega limits only: theta and rho are not monitored. */
    public GreekAlertManager(double deltaWarn, double deltaCrit,
                             double gammaWarn, double gammaCrit,
                             double vegaWarn, double vegaCrit) {
        this(deltaWarn, deltaCrit, gammaWarn, gammaCrit, vegaWarn, vegaCrit,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * All five first-order limits. A theta or rho threshold that is not finite switches that metric off: it is
     * aggregated and shown but never alerts, which is the state of a configuration without {@code risk.max_theta}
     * or {@code risk.max_rho}.
     */
    public GreekAlertManager(double deltaWarn, double deltaCrit,
                             double gammaWarn, double gammaCrit,
                             double vegaWarn, double vegaCrit,
                             double thetaWarn, double thetaCrit,
                             double rhoWarn, double rhoCrit) {
        this.deltaThresholdWarning = deltaWarn;
        this.deltaThresholdCritical = deltaCrit;
        this.gammaThresholdWarning = gammaWarn;
        this.gammaThresholdCritical = gammaCrit;
        this.vegaThresholdWarning = vegaWarn;
        this.vegaThresholdCritical = vegaCrit;
        this.thetaThresholdWarning = unlimitedIfNotFinite(thetaWarn);
        this.thetaThresholdCritical = unlimitedIfNotFinite(thetaCrit);
        this.rhoThresholdWarning = unlimitedIfNotFinite(rhoWarn);
        this.rhoThresholdCritical = unlimitedIfNotFinite(rhoCrit);
    }

    private static double unlimitedIfNotFinite(double threshold) {
        return Double.isFinite(threshold) ? threshold : Double.POSITIVE_INFINITY;
    }

    public double deltaWarning() { return deltaThresholdWarning; }
    public double deltaCritical() { return deltaThresholdCritical; }
    public double gammaWarning() { return gammaThresholdWarning; }
    public double gammaCritical() { return gammaThresholdCritical; }
    public double vegaWarning() { return vegaThresholdWarning; }
    public double vegaCritical() { return vegaThresholdCritical; }
    public double thetaWarning() { return thetaThresholdWarning; }
    public double thetaCritical() { return thetaThresholdCritical; }
    public double rhoWarning() { return rhoThresholdWarning; }
    public double rhoCritical() { return rhoThresholdCritical; }
    /** False when theta is aggregated but has no limit. */
    public boolean monitorsTheta() { return Double.isFinite(thetaThresholdCritical); }
    /** False when rho is aggregated but has no limit. */
    public boolean monitorsRho() { return Double.isFinite(rhoThresholdCritical); }

    public void addAlertListener(Consumer<GreekAlert> listener) {
        this.alertListeners.add(listener);
    }

    public void checkLimits(GreekRiskMonitor.PortfolioRisk risk) {
        long now = System.currentTimeMillis();
        
        checkMetric("Net Delta", Math.abs(risk.netDelta()), deltaThresholdWarning, deltaThresholdCritical, now);
        checkMetric("Net Gamma", Math.abs(risk.netGamma()), gammaThresholdWarning, gammaThresholdCritical, now);
        checkMetric("Net Vega", Math.abs(risk.netVega()), vegaThresholdWarning, vegaThresholdCritical, now);
        if (monitorsTheta()) {
            checkMetric("Net Theta", Math.abs(risk.netTheta()), thetaThresholdWarning, thetaThresholdCritical, now);
        }
        if (monitorsRho()) {
            checkMetric("Net Rho", Math.abs(risk.netRho()), rhoThresholdWarning, rhoThresholdCritical, now);
        }
    }

    /**
     * Edge-triggered: an alert fires only when a metric escalates to a higher severity.
     * A metric de-escalates only after dropping below {@link #HYSTERESIS} of the threshold,
     * so values hovering near a limit do not flood listeners or logs.
     */
    private synchronized void checkMetric(String metricName, double absoluteValue, double warnThreshold, double critThreshold, long timestamp) {
        AlertSeverity previous = activeState.get(metricName);
        AlertSeverity current = previous;

        if (absoluteValue >= critThreshold) {
            current = AlertSeverity.CRITICAL;
        } else if (absoluteValue >= warnThreshold) {
            if (previous != AlertSeverity.CRITICAL || absoluteValue < critThreshold * HYSTERESIS) {
                current = AlertSeverity.WARNING;
            }
        } else if (absoluteValue < warnThreshold * HYSTERESIS) {
            current = null;
        }

        if (current == null) {
            activeState.remove(metricName);
            return;
        }
        activeState.put(metricName, current);
        if (current != previous && (previous == null || current == AlertSeverity.CRITICAL)) {
            double threshold = current == AlertSeverity.CRITICAL ? critThreshold : warnThreshold;
            fireAlert(new GreekAlert(metricName, absoluteValue, threshold, current, timestamp));
        }
    }

    private void fireAlert(GreekAlert alert) {
        for (Consumer<GreekAlert> listener : alertListeners) {
            try {
                listener.accept(alert);
            } catch (Exception e) {
                System.err.println("Failed to notify alert listener: " + e.getMessage());
            }
        }
        
        // Default console logging
        String prefix = alert.severity() == AlertSeverity.CRITICAL ? "[CRITICAL ALERT]" : "[WARNING]";
        System.err.printf("%s %s breached threshold! Current: %.2f (Limit: %.2f)%n", 
                prefix, alert.metric(), alert.value(), alert.threshold());
    }
}
