#!/bin/bash
# Generate self-signed TLS certificate for the dashboard
# Usage: ./generate-selfsigned-cert.sh [DOMAIN] [KEYSTORE_PATH] [PASSWORD]
# Example: ./generate-selfsigned-cert.sh localhost dashboard.p12 changeit

set -e

DOMAIN="${1:-localhost}"
KEYSTORE_PATH="${2:-dashboard.p12}"
PASSWORD="${3:-changeit}"
VALIDITY_DAYS="${4:-365}"

echo "Generating self-signed certificate for: $DOMAIN"
echo "Keystore: $KEYSTORE_PATH"
echo "Validity: $VALIDITY_DAYS days"

# Check if keytool is available
if ! command -v keytool &> /dev/null; then
    echo "ERROR: keytool not found. Please install JDK or add it to PATH."
    exit 1
fi

# Generate self-signed certificate
keytool -genkeypair \
    -alias dashboard \
    -keyalg RSA \
    -keysize 2048 \
    -validity $VALIDITY_DAYS \
    -dname "CN=$DOMAIN, OU=Blackthorn Sterling, O=Options Pricing Engine, L=Unknown, ST=Unknown, C=US" \
    -keystore "$KEYSTORE_PATH" \
    -storetype PKCS12 \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -ext SAN=dns:$DOMAIN,ip:127.0.0.1

echo ""
echo "Certificate generated successfully!"
echo "Keystore: $KEYSTORE_PATH"
echo "Password: $PASSWORD"
echo ""
echo "To use this certificate with the dashboard:"
echo "1. Set KEYSTORE_PATH=$KEYSTORE_PATH in .env"
echo "2. Set KEYSTORE_PASSWORD=$PASSWORD in .env"
echo "3. Set TLS_ENABLED=true in .env"
echo ""
echo "For development, you may need to:"
echo "  - Import the certificate into your browser's trust store"
echo "  - Or accept the security warning when accessing https://$DOMAIN"
