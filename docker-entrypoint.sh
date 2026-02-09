#!/bin/bash
set -e

# Start MinIO in non-prod environments (for PR branch deploys / staging)
if [ "$BIFF_PROFILE" != "prod" ]; then
  echo "Non-prod environment detected. Starting MinIO..."
  mkdir -p /tmp/minio-data
  MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}" \
  minio server /tmp/minio-data --address :9000 &

  # Wait for MinIO to be ready
  MINIO_READY=false
  for i in $(seq 1 30); do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/minio/health/live | grep -q "200"; then
      echo "MinIO is ready"
      MINIO_READY=true
      break
    fi
    echo "Waiting for MinIO... ($i/30)"
    sleep 1
  done

  if [ "$MINIO_READY" != "true" ]; then
    echo "ERROR: MinIO failed to start within 30 seconds"
    exit 1
  fi

  # Create buckets
  mc alias set local http://localhost:9000 "${MINIO_ROOT_USER:-minioadmin}" "${MINIO_ROOT_PASSWORD:-minioadmin}"
  mc mb --ignore-existing local/yakread-content
  mc mb --ignore-existing local/yakread-emails
  mc mb --ignore-existing local/yakread-images
  mc mb --ignore-existing local/yakread-export
  echo "MinIO buckets created"

  # Set S3 env vars if not already set
  export S3_DEFAULT_ORIGIN="${S3_DEFAULT_ORIGIN:-http://localhost:9000}"
  export S3_DEFAULT_ACCESS_KEY="${S3_DEFAULT_ACCESS_KEY:-minioadmin}"
  export S3_DEFAULT_SECRET_KEY="${S3_DEFAULT_SECRET_KEY:-minioadmin}"
  export S3_CONTENT_BUCKET="${S3_CONTENT_BUCKET:-yakread-content}"
  export S3_EMAILS_BUCKET="${S3_EMAILS_BUCKET:-yakread-emails}"
  export S3_IMAGES_BUCKET="${S3_IMAGES_BUCKET:-yakread-images}"
  export S3_IMAGES_EDGE="${S3_IMAGES_EDGE:-http://localhost:9000/yakread-images}"
  export S3_EXPORT_BUCKET="${S3_EXPORT_BUCKET:-yakread-export}"
  export S3_EXPORT_EDGE="${S3_EXPORT_EDGE:-http://localhost:9000/yakread-export}"
fi

exec "$@"
