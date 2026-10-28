package com.sbk.optionspricer.metrics;

import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CloudWatch metrics publisher for AWS deployments.
 * 
 * Publishes engine metrics to AWS CloudWatch for monitoring and alerting.
 * Requires AWS credentials (usually from instance profile when running on EC2).
 * 
 * Disabled by default; enable by setting METRICS_CLOUDWATCH_ENABLED=true in .env
 */
public final class CloudWatchMetricsPublisher {

    private final boolean enabled;
    private final String namespace;
    private final String instanceId;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public CloudWatchMetricsPublisher() {
        this.enabled = Boolean.parseBoolean(
            EnvironmentConfigLoader.getOrDefault("METRICS_CLOUDWATCH_ENABLED", "false")
        );
        this.namespace = EnvironmentConfigLoader.getOrDefault("METRICS_CLOUDWATCH_NAMESPACE", "BlackthornSterling");
        this.instanceId = getInstanceId();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloudwatch-metrics");
            t.setDaemon(true);
            return t;
        });

        if (enabled) {
            System.out.println("[CLOUDWATCH] Metrics publisher enabled (namespace: " + namespace + ")");
        } else {
            System.out.println("[CLOUDWATCH] Metrics publisher disabled (set METRICS_CLOUDWATCH_ENABLED=true to enable)");
        }
    }

    /**
     * Starts the metrics publisher.
     */
    public void start() {
        if (!enabled || running) {
            return;
        }

        running = true;
        scheduler.scheduleAtFixedRate(this::publishHeartbeat, 0, 60, TimeUnit.SECONDS);
        System.out.println("[CLOUDWATCH] Started publishing metrics every 60 seconds");
    }

    /**
     * Stops the metrics publisher.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("[CLOUDWATCH] Stopped metrics publisher");
    }

    /**
     * Publishes a custom metric to CloudWatch.
     * 
     * @param metricName Name of the metric
     * @param value Metric value
     * @param unit Unit of the metric (Count, None, Seconds, etc.)
     */
    public void publishMetric(String metricName, double value, String unit) {
        if (!enabled || !running) {
            return;
        }

        try {
            // TODO: Implement actual CloudWatch API call using AWS SDK
            // For now, this is a placeholder that logs the metric
            System.out.printf("[CLOUDWATCH] Would publish metric: %s = %f %s%n", metricName, value, unit);
            
            // Future implementation:
            // CloudWatchClient client = CloudWatchClient.create();
            // client.putMetricData(PutMetricDataRequest.builder()
            //     .namespace(namespace)
            //     .metricData(MetricDatum.builder()
            //         .metricName(metricName)
            //         .value(value)
            //         .unit(StandardUnit.fromValue(unit))
            //         .timestamp(Instant.now())
            //         .dimensions(dimension -> dimension.name("InstanceId").value(instanceId))
            //         .build())
            //     .build());
        } catch (Exception e) {
            System.err.println("[CLOUDWATCH] Failed to publish metric " + metricName + ": " + e.getMessage());
        }
    }

    /**
     * Publishes a counter metric.
     */
    public void publishCounter(String metricName, long value) {
        publishMetric(metricName, value, "Count");
    }

    /**
     * Publishes a gauge metric.
     */
    public void publishGauge(String metricName, double value) {
        publishMetric(metricName, value, "None");
    }

    /**
     * Publishes a timing metric in milliseconds.
     */
    public void publishTiming(String metricName, double milliseconds) {
        publishMetric(metricName, milliseconds, "Milliseconds");
    }

    /**
     * Publishes a heartbeat metric.
     */
    private void publishHeartbeat() {
        publishCounter("EngineHeartbeat", 1);
    }

    /**
     * Gets the EC2 instance ID if running on EC2.
     */
    private String getInstanceId() {
        try {
            // Try to get instance ID from EC2 metadata service
            java.net.URL url = new java.net.URL("http://169.254.169.254/latest/meta-data/instance-id");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            
            if (conn.getResponseCode() == 200) {
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    return in.readLine();
                }
            }
        } catch (Exception e) {
            // Not running on EC2 or metadata service unavailable
        }
        
        return "unknown";
    }

    /**
     * Returns true if CloudWatch metrics are enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the CloudWatch namespace.
     */
    public String getNamespace() {
        return namespace;
    }
}
