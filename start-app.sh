#!/bin/bash
set -e
cd /home/sprite/repo

export JAVA_HOME=/opt/java-21
export PATH=$JAVA_HOME/bin:$PATH

# Export all vars from config.env
set -a
source config.env
set +a
export BIFF_PROFILE=dev

# Ensure MinIO is running
if ! curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/minio/health/live | grep -q "200"; then
  echo "Starting MinIO..."
  mkdir -p /tmp/minio-data
  MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin minio server /tmp/minio-data --address :9000 &
  for i in $(seq 1 30); do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/minio/health/live | grep -q "200"; then
      echo "MinIO is ready"
      break
    fi
    sleep 1
  done
fi

exec clojure -M:dev-server
