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

    private final List<Consumer<GreekAlert>> alertListeners = new ArrayList<>();

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

    private void checkMetric(String metricName, double absoluteValue, double warnThreshold, double critThreshold, long timestamp) {
        if (absoluteValue >= critThreshold) {
            fireAlert(new GreekAlert(metricName, absoluteValue, critThreshold, AlertSeverity.CRITICAL, timestamp));
        } else if (absoluteValue >= warnThreshold) {
            fireAlert(new GreekAlert(metricName, absoluteValue, warnThreshold, AlertSeverity.WARNING, timestamp));
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
