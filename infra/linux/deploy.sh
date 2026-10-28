#!/bin/bash
set -euo pipefail

# Blackthorn Sterling Linux Deployment Script
# Deploys the engine to a Linux server with systemd, monitoring, and cloud backups
# AWS-aware: automatically detects EC2 and configures CloudWatch metrics

REPO_URL="https://github.com/sivuhsGorha/options-pricing-engine.git"
INSTALL_DIR="/opt/blackthorn-sterling"
DATA_DIR="/opt/blackthorn-sterling/data"
LOG_DIR="/opt/blackthorn-sterling/logs"
BACKUP_DIR="/opt/blackthorn-sterling/backups"
SERVICE_NAME="blackthorn-sterling"
USERNAME="blackthorn"

# Detect if running on AWS
detect_aws() {
    if curl -s --connect-timeout 1 http://169.254.169.254/latest/meta-data/instance-id > /dev/null 2>&1; then
        echo "true"
    else
        echo "false"
    fi
}

IS_AWS=$(detect_aws)

echo "========================================================================="
echo "     BLACKTHORN STERLING LINUX DEPLOYMENT"
echo "========================================================================="
if [ "$IS_AWS" = "true" ]; then
    echo "AWS environment detected - will configure CloudWatch metrics"
else
    echo "Non-AWS environment detected - using Prometheus monitoring"
fi
echo ""

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "This script must be run as root (use sudo)"
    exit 1
fi

# Create dedicated user
echo "[1/7] Creating dedicated user $USERNAME..."
if ! id "$USERNAME" &>/dev/null; then
    useradd -r -s /bin/bash -d "$INSTALL_DIR" "$USERNAME"
    echo "User $USERNAME created"
else
    echo "User $USERNAME already exists"
fi

# Install dependencies
echo "[2/7] Installing dependencies..."
apt-get update
apt-get install -y docker.io docker-compose git curl jq python3-pip
systemctl enable docker
systemctl start docker

# Clone or update repository
echo "[3/7] Cloning repository..."
if [ -d "$INSTALL_DIR/.git" ]; then
    echo "Repository exists, pulling latest..."
    cd "$INSTALL_DIR"
    sudo -u "$USERNAME" git pull
else
    echo "Cloning fresh repository..."
    rm -rf "$INSTALL_DIR"
    git clone "$REPO_URL" "$INSTALL_DIR"
    chown -R "$USERNAME:$USERNAME" "$INSTALL_DIR"
fi

# Create directories
echo "[4/7] Creating directories..."
mkdir -p "$DATA_DIR" "$LOG_DIR" "$BACKUP_DIR"
chown -R "$USERNAME:$USERNAME" "$DATA_DIR" "$LOG_DIR" "$BACKUP_DIR"

# Copy configuration template
echo "[5/7] Setting up configuration..."
if [ ! -f "$INSTALL_DIR/config.yaml" ]; then
    cp "$INSTALL_DIR/config.example.yaml" "$INSTALL_DIR/config.yaml"
    chown "$USERNAME:$USERNAME" "$INSTALL_DIR/config.yaml"
    echo "Created config.yaml from template - EDIT THIS FILE WITH YOUR SETTINGS"
fi

if [ ! -f "$INSTALL_DIR/.env" ]; then
    cp "$INSTALL_DIR/.env.example" "$INSTALL_DIR/.env"
    chown "$USERNAME:$USERNAME" "$INSTALL_DIR/.env"
    echo "Created .env from template - EDIT THIS FILE WITH YOUR SECRETS"
fi

# AWS-specific configuration
if [ "$IS_AWS" = "true" ]; then
    echo "[5/7] Configuring AWS-specific settings..."
    
    # Get AWS region from instance metadata
    AWS_REGION=$(curl -s http://169.254.169.254/latest/meta-data/placement/region 2>/dev/null || echo "us-east-1")
    
    # Add AWS settings to .env
    {
        echo ""
        echo "# AWS-specific settings"
        echo "AWS_REGION=$AWS_REGION"
        echo "METRICS_CLOUDWATCH_ENABLED=true"
        echo "METRICS_CLOUDWATCH_NAMESPACE=BlackthornSterling"
    } >> "$INSTALL_DIR/.env"
    
    echo "Added AWS configuration to .env (region: $AWS_REGION)"
fi

# Install systemd service
echo "[6/7] Installing systemd service..."
cp "$INSTALL_DIR/infra/linux/blackthorn-sterling.service" "/etc/systemd/system/$SERVICE_NAME.service"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"

# Build the application
echo "[7/7] Building application..."
cd "$INSTALL_DIR"
sudo -u "$USERNAME" ./mvnw clean package -DskipTests

echo "========================================================================="
echo "     DEPLOYMENT COMPLETE"
echo "========================================================================="
echo ""
echo "Next steps:"
echo "1. Edit $INSTALL_DIR/config.yaml with your settings"
echo "2. Edit $INSTALL_DIR/.env with your API keys and secrets"
echo "3. Start the service: sudo systemctl start $SERVICE_NAME"
echo "4. Check status: sudo systemctl status $SERVICE_NAME"
echo "5. View logs: sudo journalctl -u $SERVICE_NAME -f"
echo ""
if [ "$IS_AWS" = "true" ]; then
    echo "AWS deployment detected:"
    echo "- CloudWatch metrics enabled (see AWS CloudWatch console)"
    echo "- AWS region: $AWS_REGION"
    echo "- Instance ID: $(curl -s http://169.254.169.254/latest/meta-data/instance-id)"
    echo ""
fi
echo "Data directory: $DATA_DIR"
echo "Log directory: $LOG_DIR"
echo "Backup directory: $BACKUP_DIR"
echo ""
