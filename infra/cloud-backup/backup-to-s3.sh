#!/bin/bash
set -euo pipefail

# Blackthorn Sterling AWS S3 Backup Script
# Backs up data directory to S3 with retention policy

# Configuration
DATA_DIR="${DATA_DIR:-/opt/blackthorn-sterling/data}"
S3_BUCKET="${S3_BUCKET:-blackthorn-sterling-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
LOCK_FILE="/tmp/blackthorn-backup.lock"
LOG_FILE="/var/log/blackthorn-backup.log"

# Ensure only one backup runs at a time
exec 200>"$LOCK_FILE"
flock -n 200 || {
    echo "[$(date -Iseconds)] Backup already in progress, exiting" >> "$LOG_FILE"
    exit 1
}

log() {
    echo "[$(date -Iseconds)] $1" | tee -a "$LOG_FILE"
}

cleanup_old_backups() {
    log "Cleaning up backups older than $RETENTION_DAYS days..."
    aws s3 ls "s3://$S3_BUCKET/" --recursive | while read -r line; do
        file_date=$(echo "$line" | awk '{print $1, $2}')
        file_path=$(echo "$line" | awk '{print $4}')
        file_timestamp=$(date -d "$file_date" +%s)
        cutoff_timestamp=$(date -d "$RETENTION_DAYS days ago" +%s)

        if [ "$file_timestamp" -lt "$cutoff_timestamp" ]; then
            log "Deleting old backup: $file_path"
            aws s3 rm "s3://$S3_BUCKET/$file_path"
        fi
    done
}

backup_data() {
    local timestamp=$(date -u +"%Y%m%dT%H%M%SZ")
    local backup_name="blackthorn-backup-$timestamp.tar.gz"
    local temp_file="/tmp/$backup_name"

    log "Starting backup to S3..."
    log "Data directory: $DATA_DIR"
    log "S3 bucket: $S3_BUCKET"
    log "Backup name: $backup_name"

    # Create compressed archive
    log "Creating compressed archive..."
    tar -czf "$temp_file" -C "$DATA_DIR" . || {
        log "ERROR: Failed to create archive"
        rm -f "$temp_file"
        exit 1
    }

    # Upload to S3
    log "Uploading to S3..."
    aws s3 cp "$temp_file" "s3://$S3_BUCKET/$backup_name" || {
        log "ERROR: Failed to upload to S3"
        rm -f "$temp_file"
        exit 1
    }

    # Set lifecycle policy
    log "Setting lifecycle policy..."
    aws s3api put-object-lifecycle-configuration \
        --bucket "$S3_BUCKET" \
        --lifecycle-configuration '{
            "Rules": [
                {
                    "Id": "DeleteOldBackups",
                    "Status": "Enabled",
                    "Filter": {"Prefix": ""},
                    "Expiration": {"Days": '$RETENTION_DAYS'}
                }
            ]
        }' || log "WARNING: Failed to set lifecycle policy"

    # Cleanup
    rm -f "$temp_file"
    log "Backup completed successfully: s3://$S3_BUCKET/$backup_name"
}

# Main execution
log "=========================================="
log "Starting Blackthorn Sterling S3 Backup"
log "=========================================="

# Check AWS CLI is installed
if ! command -v aws &> /dev/null; then
    log "ERROR: AWS CLI not installed"
    exit 1
fi

# Check S3 bucket exists
if ! aws s3 ls "s3://$S3_BUCKET" &> /dev/null; then
    log "Creating S3 bucket: $S3_BUCKET"
    aws s3 mb "s3://$S3_BUCKET" || {
        log "ERROR: Failed to create S3 bucket"
        exit 1
    }
fi

# Run backup
backup_data

# Cleanup old backups
cleanup_old_backups

log "=========================================="
log "Backup process completed"
log "=========================================="
