#!/bin/bash
# External heartbeat monitoring script
# Checks if the engine heartbeat file is stale and optionally sends an alert
# Usage: ./check_heartbeat.sh [HEARTBEAT_FILE] [STALE_THRESHOLD_SECONDS] [NTFY_TOPIC] [WEBHOOK_URL]

set -e

# Default values
HEARTBEAT_FILE="${1:-data/heartbeat.txt}"
STALE_THRESHOLD_SECONDS="${2:-90}"
NTFY_TOPIC="${3:-}"
WEBHOOK_URL="${4:-}"

# Check if heartbeat file exists
if [ ! -f "$HEARTBEAT_FILE" ]; then
    echo "ERROR: Heartbeat file not found: $HEARTBEAT_FILE"
    echo "This indicates the engine has never started or the file was deleted."
    exit 1
fi

# Get file modification time in seconds since epoch
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    LAST_MODIFIED=$(stat -f %m "$HEARTBEAT_FILE")
else
    # Linux
    LAST_MODIFIED=$(stat -c %Y "$HEARTBEAT_FILE")
fi

CURRENT_TIME=$(date +%s)
AGE_SECONDS=$((CURRENT_TIME - LAST_MODIFIED))

# Check if stale
if [ $AGE_SECONDS -gt $STALE_THRESHOLD_SECONDS ]; then
    echo "WARNING: Heartbeat is stale (age: ${AGE_SECONDS}s, threshold: ${STALE_THRESHOLD_SECONDS}s)"
    echo "This indicates a crash, power loss, or extended standby."

    # Read heartbeat content for context
    if [ -r "$HEARTBEAT_FILE" ]; then
        echo "Heartbeat content:"
        cat "$HEARTBEAT_FILE"
    fi

    # Send alert if configured
    if [ -n "$NTFY_TOPIC" ]; then
        echo "Sending alert to ntfy.sh topic: $NTFY_TOPIC"
        curl -s -X POST "https://ntfy.sh/$NTFY_TOPIC" \
            -H "Title: [Blackthorn Sterling] ENGINE HEARTBEAT STALE" \
            -H "Priority: urgent" \
            -H "Tags: warning" \
            -d "Engine heartbeat is stale (age ${AGE_SECONDS}s, threshold ${STALE_THRESHOLD_SECONDS}s). This indicates a crash, power loss, or extended standby." || true
    fi

    if [ -n "$WEBHOOK_URL" ]; then
        echo "Sending alert to webhook"
        curl -s -X POST "$WEBHOOK_URL" \
            -H "Content-Type: application/json" \
            -d "{\"title\":\"[Blackthorn Sterling] ENGINE HEARTBEAT STALE\",\"message\":\"Engine heartbeat is stale (age ${AGE_SECONDS}s, threshold ${STALE_THRESHOLD_SECONDS}s)\",\"priority\":\"urgent\",\"timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" || true
    fi

    exit 1
else
    echo "OK: Heartbeat is recent (age: ${AGE_SECONDS}s, threshold: ${STALE_THRESHOLD_SECONDS}s)"
    exit 0
fi
