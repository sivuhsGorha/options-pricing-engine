# Operations Guide

This guide covers operational aspects of Blackthorn Sterling, including Linux deployment, monitoring, auto-hedging, and dynamic risk limits.

## Linux Deployment

### Quick Start

```bash
# Clone the repository
git clone https://github.com/sivuhsGorha/options-pricing-engine.git
cd options-pricing-engine

# Run the deployment script
sudo ./infra/linux/deploy.sh

# Edit configuration
sudo nano /opt/blackthorn-sterling/config.yaml
sudo nano /opt/blackthorn-sterling/.env

# Start the service
sudo systemctl start blackthorn-sterling

# Check status
sudo systemctl status blackthorn-sterling
```

### Service Management

```bash
# Start
sudo systemctl start blackthorn-sterling

# Stop
sudo systemctl stop blackthorn-sterling

# Restart
sudo systemctl restart blackthorn-sterling

# View logs
sudo journalctl -u blackthorn-sterling -f

# Enable at boot
sudo systemctl enable blackthorn-sterling
```

See `infra/linux/README.md` for detailed deployment instructions, monitoring setup, and SSL/TLS configuration.

## Auto-Hedging

### Configuration

Auto-hedging is configured in `config.yaml` under `risk.auto_hedge`:

```yaml
risk:
  auto_hedge:
    enabled: true                   # enable automatic delta hedging
    hedge_band: 50                   # hedge when |net delta| exceeds this many shares
    mode: immediate                  # immediate | threshold | adaptive
    min_hedge_size: 10               # minimum hedge size in shares
    hedge_duration_seconds: 60      # minimum duration delta must be exceeded (threshold mode)
```

### Hedging Modes

- **IMMEDIATE**: Hedge immediately when delta exceeds the band
- **THRESHOLD**: Hedge only after delta has been outside the band for `hedge_duration_seconds`
- **ADAPTIVE**: Hedge based on volatility regime (dynamic bands, requires dynamic_limits enabled)

### Monitoring

Auto-hedging status is visible in the dashboard:
- Current net delta
- Whether delta is exceeding the band
- Time since delta exceeded (threshold mode)
- Last hedge order details

## Dynamic Greek Limits

### Configuration

Dynamic Greek limits are configured in `config.yaml` under `risk.dynamic_limits`:

```yaml
risk:
  dynamic_limits:
    enabled: true                   # enable dynamic Greek limits
    window_size: 60                 # number of volatility points to consider
    low_threshold: 0.5               # volatility below this (relative to average) is LOW regime
    high_threshold: 1.5             # volatility above this is HIGH regime
    extreme_threshold: 2.5           # volatility above this is EXTREME regime
```

### Regime Multipliers

| Regime | Limit Multiplier | Description |
|--------|------------------|-------------|
| LOW | 0.5x | Calm market, tighter limits |
| NORMAL | 1.0x | Baseline regime |
| HIGH | 1.5x | Elevated risk, wider limits |
| EXTREME | 2.0x | Crisis conditions, widest limits |

### Benefits

- Prevents unnecessary halts during calm periods
- Provides wider limits during volatility spikes
- Adapts to changing market conditions automatically

## Real-Time Data Infrastructure

### IBKR Integration (Placeholder)

The `IbkrOptionChainProvider` is a placeholder for full IBKR TWS API integration. To complete:

1. Install IBKR TWS or IB Gateway
2. Enable API connections in TWS settings
3. Configure API credentials in `.env`:
   ```
   IBKR_HOST=127.0.0.1
   IBKR_PORT=7497
   IBKR_CLIENT_ID=1
   ```
4. Add IBKR dependency to `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.ib.client</groupId>
       <artifactId>tws-api</artifactId>
       <version>10.21</version>
   </dependency>
   ```
5. Implement full EWrapper callbacks in `IbkrOptionChainProvider`

### WebSocket Spot Streaming

The `WebSocketSpotProvider` adds latency tracking infrastructure. Configuration:

```yaml
# In .env
WEBSOCKET_STALENESS_SECONDS=30    # staleness threshold for WebSocket data
```

Latency metrics are visible in the dashboard and exposed via `/api/health`.

## Monitoring and Alerting

### Prometheus Metrics

The engine exposes metrics at `/metrics`:
- Engine health and uptime
- Market data age and status
- Trading halt status
- Latency metrics (p50, p95, p99)
- Orders attempted and filled
- Slippage statistics
- Net Greeks
- P&L

### Alert Rules

See `infra/linux/monitoring/alert-rules.yml` for configured alerts:
- Engine down
- Trading halted
- Greek limit breaches
- Market data staleness
- High latency
- Low fill rate
- Reconciliation mismatch
- Large P&L drawdown
- High CPU/memory usage

### Grafana Dashboards

Import dashboards from `infra/linux/monitoring/grafana-dashboards/`:
- Blackthorn Sterling Overview
- System Resources
- Execution Quality

## Cloud Backups

### AWS S3 Backup

```bash
# Install AWS CLI
sudo apt-get install -y awscli

# Configure credentials
aws configure

# Make script executable
chmod +x infra/cloud-backup/backup-to-s3.sh

# Configure environment variables
export S3_BUCKET=blackthorn-sterling-backups
export RETENTION_DAYS=30

# Test backup
./infra/cloud-backup/backup-to-s3.sh

# Add to crontab (daily at 16:30 UTC)
crontab -e
# Add: 30 16 * * * /opt/blackthorn-sterling/infra/cloud-backup/backup-to-s3.sh >> /var/log/blackthorn-backup.log 2>&1
```

### Google Cloud Storage Backup

```bash
# Install gsutil
curl https://sdk.cloud.google.com | bash
exec -l $SHELL
gcloud init

# Make script executable
chmod +x infra/cloud-backup/backup-to-gcs.sh

# Configure environment variables
export GCS_BUCKET=blackthorn-sterling-backups
export RETENTION_DAYS=30

# Test backup
./infra/cloud-backup/backup-to-gcs.sh

# Add to crontab
crontab -e
# Add: 30 16 * * * /opt/blackthorn-sterling/infra/cloud-backup/backup-to-gcs.sh >> /var/log/blackthorn-backup-gcs.log 2>&1
```

## Troubleshooting

### Auto-hedging not working

1. Check if enabled in config: `risk.auto_hedge.enabled: true`
2. Verify hedge band is appropriate for your position size
3. Check if order manager is accepting orders
4. Review logs: `sudo journalctl -u blackthorn-sterling -f | grep -i hedge`

### Dynamic limits not adjusting

1. Check if enabled in config: `risk.dynamic_limits.enabled: true`
2. Verify volatility data is being collected
3. Check regime classification in dashboard
4. Review regime detector logs

### High latency on WebSocket data

1. Check `WEBSOCKET_STALENESS_SECONDS` threshold
2. Verify network connectivity to data provider
3. Check latency metrics in `/api/health`
4. Consider moving engine closer to data source (cloud deployment)

### Backup failures

1. Check backup logs: `sudo tail -f /var/log/blackthorn-backup.log`
2. Verify AWS/GCS credentials
3. Check bucket permissions
4. Ensure data directory exists and is accessible
