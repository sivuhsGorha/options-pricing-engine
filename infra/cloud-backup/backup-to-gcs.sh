#!/bin/bash
set -euo pipefail

# Blackthorn Sterling Google Cloud Storage Backup Script
# Backs up data directory to GCS with retention policy

# Configuration
DATA_DIR="${DATA_DIR:-/opt/blackthorn-sterling/data}"
GCS_BUCKET="${GCS_BUCKET:-blackthorn-sterling-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
LOCK_FILE="/tmp/blackthorn-backup-gcs.lock"
LOG_FILE="/var/log/blackthorn-backup-gcs.log"

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
    gsutil ls "gs://$GCS_BUCKET/**" 2>/dev/null | while read -r file_path; do
        file_date=$(echo "$file_path" | grep -oP '\d{8}T\d{6}Z' | head -1)
        if [ -n "$file_date" ]; then
            file_timestamp=$(date -d "${file_date:0:8} ${file_date:9:6}" +%s 2>/dev/null || continue)
            cutoff_timestamp=$(date -d "$RETENTION_DAYS days ago" +%s)

            if [ "$file_timestamp" -lt "$cutoff_timestamp" ]; then
                log "Deleting old backup: $file_path"
                gsutil rm "$file_path"
            fi
        fi
    done
}

backup_data() {
    local timestamp=$(date -u +"%Y%m%dT%H%M%SZ")
    local backup_name="blackthorn-backup-$timestamp.tar.gz"
    local temp_file="/tmp/$backup_name"

    log "Starting backup to GCS..."
    log "Data directory: $DATA_DIR"
    log "GCS bucket: $GCS_BUCKET"
    log "Backup name: $backup_name"

    # Create compressed archive
    log "Creating compressed archive..."
    tar -czf "$temp_file" -C "$DATA_DIR" . || {
        log "ERROR: Failed to create archive"
        rm -f "$temp_file"
        exit 1
    }

    # Upload to GCS
    log "Uploading to GCS..."
    gsutil cp "$temp_file" "gs://$GCS_BUCKET/$backup_name" || {
        log "ERROR: Failed to upload to GCS"
        rm -f "$temp_file"
        exit 1
    }

    # Cleanup
    rm -f "$temp_file"
    log "Backup completed successfully: gs://$GCS_BUCKET/$backup_name"
}

# Main execution
log "=========================================="
log "Starting Blackthorn Sterling GCS Backup"
log "=========================================="

# Check gsutil is installed
if ! command -v gsutil &> /dev/null; then
    log "ERROR: gsutil not installed"
    exit 1
fi

# Check GCS bucket exists
if ! gsutil ls "gs://$GCS_BUCKET" &> /dev/null; then
    log "Creating GCS bucket: $GCS_BUCKET"
    gsutil mb "gs://$GCS_BUCKET" || {
        log "ERROR: Failed to create GCS bucket"
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
