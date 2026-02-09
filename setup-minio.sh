#!/bin/bash
# Shared script to install, start, and configure MinIO for CI workflows.
# Used by both copilot-setup-steps.yml and playwright-tests.yml.
set -e

# Install MinIO server
curl -L -o /usr/local/bin/minio https://dl.min.io/server/minio/release/linux-amd64/minio
chmod +x /usr/local/bin/minio

# Start MinIO
mkdir -p /tmp/minio-data
MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin minio server /tmp/minio-data --address :9000 &

# Wait for MinIO to be ready
for i in $(seq 1 30); do
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/minio/health/live | grep -q "200"; then
    echo "MinIO is ready"
    break
  fi
  echo "Waiting for MinIO... ($i/30)"
  sleep 1
done

# Install MinIO client and create buckets
curl -L -o /usr/local/bin/mc https://dl.min.io/client/mc/release/linux-amd64/mc
chmod +x /usr/local/bin/mc
mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb local/yakread-content
mc mb local/yakread-emails
mc mb local/yakread-images
mc mb local/yakread-export
