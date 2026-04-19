#!/bin/sh
# Polaris bootstrap: creates catalog for Fluss/Iceberg tiering.
set -e

POLARIS_URL="http://polaris:8181"
CLIENT_ID="root"
CLIENT_SECRET="s3cr3t"
CATALOG_NAME="fluss"

echo "Waiting for Polaris at ${POLARIS_URL}..."
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    if curl -sf -X POST "${POLARIS_URL}/api/catalog/v1/oauth/tokens" \
        -d "grant_type=client_credentials&client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}&scope=PRINCIPAL_ROLE:ALL" > /dev/null 2>&1; then
        echo "Polaris is ready."
        break
    fi
    echo "  Attempt $i — waiting..."
    sleep 3
done

# Get token
TOKEN=$(curl -sf -X POST "${POLARIS_URL}/api/catalog/v1/oauth/tokens" \
    -d "grant_type=client_credentials&client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}&scope=PRINCIPAL_ROLE:ALL" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "ERROR: Failed to obtain OAuth token"
    exit 1
fi
echo "Token obtained."

# Create catalog with S3-compatible storage (LocalStack)
# S3 config goes in catalog properties, not storageConfigInfo
echo "Creating catalog '${CATALOG_NAME}'..."
HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" -X POST "${POLARIS_URL}/api/management/v1/catalogs" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"catalog\":{\"name\":\"${CATALOG_NAME}\",\"type\":\"INTERNAL\",\"properties\":{\"default-base-location\":\"s3a://iceberg-warehouse/\",\"client.region\":\"us-east-1\",\"s3.endpoint\":\"http://localstack:4566\",\"s3.access-key-id\":\"test\",\"s3.secret-access-key\":\"test\",\"s3.path-style-access\":\"true\"},\"storageConfigInfo\":{\"storageType\":\"S3\",\"allowedLocations\":[\"s3a://iceberg-warehouse/\"],\"stsUnavailable\":true,\"endpoint\":\"http://localstack:4566\",\"pathStyleAccess\":true}}}" 2>&1 || echo "000")

echo "Catalog creation HTTP status: ${HTTP_CODE}"
if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "201" ]; then
    echo "Catalog '${CATALOG_NAME}' created successfully."
elif [ "$HTTP_CODE" = "409" ]; then
    echo "Catalog '${CATALOG_NAME}' already exists."
else
    echo "WARNING: Unexpected status ${HTTP_CODE}"
fi

echo ""
echo "=== Polaris Setup Complete ==="
echo "Catalog:    ${CATALOG_NAME}"
echo "REST URI:   ${POLARIS_URL}/api/catalog"
echo "Credential: ${CLIENT_ID}:${CLIENT_SECRET}"
