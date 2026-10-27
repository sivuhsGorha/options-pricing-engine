# Linux Deployment Guide

This guide covers deploying Blackthorn Sterling to a Linux server with systemd, monitoring, and cloud backups.

## Prerequisites

- Ubuntu 20.04+ or Debian 11+
- Root/sudo access
- Docker and Docker Compose
- Git
- At least 4GB RAM and 2 CPU cores
- AWS CLI (for S3 backups) or gsutil (for GCS backups)

## Quick Start

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

## Service Management

```bash
# Start
sudo systemctl start blackthorn-sterling

# Stop
sudo systemctl stop blackthorn-sterling

# Restart
sudo systemctl restart blackthorn-sterling

# Reload (graceful restart)
sudo systemctl reload blackthorn-sterling

# View logs
sudo journalctl -u blackthorn-sterling -f

# Enable at boot
sudo systemctl enable blackthorn-sterling
```

## Monitoring Setup

### Install Prometheus and Alertmanager

```bash
# Install Prometheus
wget https://github.com/prometheus/prometheus/releases/download/v2.45.0/prometheus-2.45.0.linux-amd64.tar.gz
tar xvfz prometheus-2.45.0.linux-amd64.tar.gz
sudo mv prometheus-2.45.0.linux-amd64 /opt/prometheus
sudo useradd --no-create-home --shell /bin/false prometheus
sudo chown -R prometheus:prometheus /opt/prometheus

# Install Alertmanager
wget https://github.com/prometheus/alertmanager/releases/download/v0.25.0/alertmanager-0.25.0.linux-amd64.tar.gz
tar xvfz alertmanager-0.25.0.linux-amd64.tar.gz
sudo mv alertmanager-0.25.0.linux-amd64 /opt/alertmanager
sudo useradd --no-create-home --shell /bin/false alertmanager
sudo chown -R alertmanager:alertmanager /opt/alertmanager

# Copy configurations
sudo cp infra/linux/monitoring/prometheus.yml /etc/prometheus/
sudo cp infra/linux/monitoring/alert-rules.yml /etc/prometheus/rules/
sudo chown prometheus:prometheus /etc/prometheus/prometheus.yml
sudo chown prometheus:prometheus /etc/prometheus/rules/alert-rules.yml

# Create systemd services
sudo tee /etc/systemd/system/prometheus.service > /dev/null <<EOF
[Unit]
Description=Prometheus
After=network.target

[Service]
User=prometheus
Group=prometheus
Type=simple
ExecStart=/opt/prometheus/prometheus \
    --config.file /etc/prometheus/prometheus.yml \
    --storage.tsdb.path /opt/prometheus/data

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable prometheus
sudo systemctl start prometheus
```

### Install Grafana

```bash
# Install Grafana
sudo apt-get install -y apt-transport-https
sudo wget -q -O /usr/share/keyrings/grafana.key https://packages.grafana.com/gpg.key
echo "deb [signed-by=/usr/share/keyrings/grafana.key] https://packages.grafana.com/debian stable main" | sudo tee -a /etc/apt/sources.list.d/grafana.list
sudo apt-get update
sudo apt-get install -y grafana

sudo systemctl enable grafana-server
sudo systemctl start grafana-server

# Access Grafana at http://your-server:3000 (default admin/admin)
```

## Cloud Backup Setup

### AWS S3 Backup

```bash
# Install AWS CLI
sudo apt-get install -y awscli

# Configure AWS credentials
aws configure

# Make backup script executable
chmod +x infra/cloud-backup/backup-to-s3.sh

# Add to crontab (runs daily at 16:30 UTC)
crontab -e
# Add this line:
30 16 * * * /opt/blackthorn-sterling/infra/cloud-backup/backup-to-s3.sh >> /var/log/blackthorn-backup.log 2>&1
```

### Google Cloud Storage Backup

```bash
# Install gsutil
curl https://sdk.cloud.google.com | bash
exec -l $SHELL
gcloud init

# Make backup script executable
chmod +x infra/cloud-backup/backup-to-gcs.sh

# Add to crontab
crontab -e
# Add this line:
30 16 * * * /opt/blackthorn-sterling/infra/cloud-backup/backup-to-gcs.sh >> /var/log/blackthorn-backup-gcs.log 2>&1
```

## Firewall Configuration

```bash
# Allow only necessary ports
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 8080/tcp  # Dashboard (HTTP)
sudo ufw allow 8081/tcp  # WebSocket
sudo ufw allow 9090/tcp  # Prometheus
sudo ufw allow 3000/tcp  # Grafana
sudo ufw enable
```

## SSL/TLS Setup

For production, use HTTPS with a reverse proxy (nginx):

```bash
# Install nginx
sudo apt-get install -y nginx certbot python3-certbot-nginx

# Configure reverse proxy
sudo tee /etc/nginx/sites-available/blackthorn-sterling > /dev/null <<EOF
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location /ws {
        proxy_pass http://localhost:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
EOF

sudo ln -s /etc/nginx/sites-available/blackthorn-sterling /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx

# Get SSL certificate
sudo certbot --nginx -d your-domain.com
```

## Troubleshooting

### Service won't start
```bash
# Check logs
sudo journalctl -u blackthorn-sterling -n 50

# Check if Docker is running
sudo systemctl status docker

# Check if ports are already in use
sudo netstat -tulpn | grep 8080
```

### High memory usage
```bash
# Check Docker container memory
docker stats

# Adjust memory limit in systemd service
sudo nano /etc/systemd/system/blackthorn-sterling.service
# Change MemoryMax=4G to desired value
sudo systemctl daemon-reload
sudo systemctl restart blackthorn-sterling
```

### Backup failures
```bash
# Check backup logs
sudo tail -f /var/log/blackthorn-backup.log

# Test AWS/GCS credentials
aws s3 ls
# or
gsutil ls
```

## Monitoring Dashboards

Import the following dashboards into Grafana:

1. **Blackthorn Sterling Overview**: Shows engine health, trading status, Greeks, P&L
2. **System Resources**: CPU, memory, disk, network metrics
3. **Execution Quality**: Fill rates, latency, slippage

Dashboard JSON files are available in `infra/linux/monitoring/grafana-dashboards/`
