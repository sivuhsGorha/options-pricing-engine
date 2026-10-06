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

    /** Fraction of a threshold the value must fall below before an alert state clears. */
    private static final double HYSTERESIS = 0.90;

    private final List<Consumer<GreekAlert>> alertListeners = new ArrayList<>();
    private final java.util.Map<String, AlertSeverity> activeState = new java.util.HashMap<>();

    public GreekAlertManager(double deltaWarn, double deltaCrit,
                             double gammaWarn, double gammaCrit,
                             double vegaWarn, double vegaCrit) {
        this.deltaThresholdWarning = deltaWarn;
        this.deltaThresholdCritical = deltaCrit;
        this.gammaThresholdWarning = gammaWarn;
        this.gammaThresholdCritical = gammaCrit;
        this.vegaThresholdWarning = vegaWarn;
        this.vegaThresholdCritical = vegaCrit;
    }

    public void addAlertListener(Consumer<GreekAlert> listener) {
        this.alertListeners.add(listener);
    }

    public void checkLimits(GreekRiskMonitor.PortfolioRisk risk) {
        long now = System.currentTimeMillis();
        
        checkMetric("Net Delta", Math.abs(risk.netDelta()), deltaThresholdWarning, deltaThresholdCritical, now);
        checkMetric("Net Gamma", Math.abs(risk.netGamma()), gammaThresholdWarning, gammaThresholdCritical, now);
        checkMetric("Net Vega", Math.abs(risk.netVega()), vegaThresholdWarning, vegaThresholdCritical, now);
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
