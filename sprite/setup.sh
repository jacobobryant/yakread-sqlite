#!/usr/bin/env bash
# sprite-setup.sh — Install dependencies and start the Yakread app as a sprite service.
# Inspired by server-setup.sh and the CI workflows.
set -euo pipefail

REPO_DIR="/home/sprite/repo"
cd "$REPO_DIR"

echo "=== Installing Java 21 (Temurin) ==="
JAVA21_DIR="/usr/lib/jvm"
if [ ! -d "$JAVA21_DIR"/jdk-21* ]; then
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" -o /tmp/jdk21.tar.gz
  mkdir -p "$JAVA21_DIR"
  tar -xzf /tmp/jdk21.tar.gz -C "$JAVA21_DIR"
  rm /tmp/jdk21.tar.gz
fi
export JAVA_HOME=$(ls -d "$JAVA21_DIR"/jdk-21* | head -1)
export PATH=$JAVA_HOME/bin:$PATH
echo "Java 21 installed at $JAVA_HOME"

echo "=== Installing Clojure CLI ==="
if ! command -v clojure &> /dev/null; then
  curl -fsSL https://download.clojure.org/install/linux-install-1.12.0.1530.sh | bash
fi

echo "=== Installing Babashka ==="
if ! command -v bb &> /dev/null; then
  curl -fsSL https://raw.githubusercontent.com/babashka/babashka/master/install | bash
fi

echo "=== Installing bbin and clojure-mcp-light tools ==="
if ! command -v bbin &> /dev/null; then
  curl -o- -L https://raw.githubusercontent.com/babashka/bbin/v0.2.4/bbin > /usr/local/bin/bbin
  chmod +x /usr/local/bin/bbin
fi

# Pre-download Clojure tools for babashka deps
BB_CLJ_VERSION=$(bb -e '(System/getProperty "babashka.deps.clojure.tools.version")' 2>/dev/null || echo "1.12.4.1597")
TOOLS_DIR="$HOME/.deps.clj/${BB_CLJ_VERSION}/ClojureTools"
if [ ! -f "${TOOLS_DIR}/clojure-tools-${BB_CLJ_VERSION}.jar" ]; then
  mkdir -p "${TOOLS_DIR}"
  curl -L -o "${TOOLS_DIR}/clojure-tools.zip" \
    "https://github.com/clojure/brew-install/releases/download/${BB_CLJ_VERSION}/clojure-tools.zip"
  cd "${TOOLS_DIR}" && unzip -o clojure-tools.zip && mv ClojureTools/* . 2>/dev/null; true
  cd "$REPO_DIR"
fi

if ! command -v clj-paren-repair &> /dev/null; then
  bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.1 --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
fi
if ! command -v clj-nrepl-eval &> /dev/null; then
  bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.1 --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
fi

echo "=== Installing sqlite3def ==="
if ! command -v sqlite3def &> /dev/null; then
  ./setup-sqlite3def.sh
fi

echo "=== Installing and starting MinIO ==="
if ! command -v minio &> /dev/null; then
  curl -L -o /usr/local/bin/minio https://dl.min.io/server/minio/release/linux-amd64/minio
  chmod +x /usr/local/bin/minio
fi
if ! command -v mc &> /dev/null; then
  curl -L -o /usr/local/bin/mc https://dl.min.io/client/mc/release/linux-amd64/mc
  chmod +x /usr/local/bin/mc
fi

echo "=== Installing Tailwind CSS standalone CLI ==="
TAILWIND_BIN="/usr/local/bin/tailwindcss"
if [ ! -f "$TAILWIND_BIN" ]; then
  curl -fsSL -o "$TAILWIND_BIN" https://github.com/tailwindlabs/tailwindcss/releases/download/v3.4.17/tailwindcss-linux-x64
  chmod +x "$TAILWIND_BIN"
fi
echo "Tailwind CSS installed at $TAILWIND_BIN"

echo "=== Downloading vendor JS dependencies ==="
./download-vendor-deps.sh

echo "=== Installing npm dependencies ==="
npm install
cd cloud-fns/packages/yakread/readability && npm install && cd "$REPO_DIR"

echo "=== Downloading Clojure dependencies ==="
clojure -P -M:dev-server

echo "=== Creating config.env ==="
if [ ! -f config.env ]; then
  cat > config.env << 'EOF'
DOMAIN=localhost
NREPL_PORT=7888
COOKIE_SECRET=YWFhYWFhYWFhYWFhYWFhYQ==
JWT_SECRET=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
ADMIN_IDS='#{}'
XTDB_TOPOLOGY=standalone
S3_DEFAULT_ORIGIN=http://localhost:9000
S3_DEFAULT_ACCESS_KEY=minioadmin
S3_DEFAULT_SECRET_KEY=minioadmin
S3_CONTENT_BUCKET=yakread-content
S3_EMAILS_BUCKET=yakread-emails
S3_IMAGES_BUCKET=yakread-images
S3_IMAGES_EDGE=http://localhost:9000/yakread-images
S3_EXPORT_BUCKET=yakread-export
S3_EXPORT_EDGE=http://localhost:9000/yakread-export
CLOUD_FN_LOCAL=true
EOF
  echo "config.env created"
else
  echo "config.env already exists, skipping"
fi

echo "=== Making scripts executable ==="
chmod +x "$REPO_DIR/sprite/start-app.sh"
chmod +x "$REPO_DIR/sprite/start-tailwind.sh"

echo "=== Creating MinIO sprite service ==="
sprite-env services create minio \
  --cmd /usr/local/bin/minio \
  --args "server,/tmp/minio-data,--address,:9000" \
  --env "MINIO_ROOT_USER=minioadmin,MINIO_ROOT_PASSWORD=minioadmin" \
  --no-stream 2>/dev/null || echo "minio service may already exist"

# Wait for MinIO to be ready
echo "Waiting for MinIO..."
for i in $(seq 1 30); do
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/minio/health/live 2>/dev/null | grep -q "200"; then
    echo "MinIO is ready"
    break
  fi
  sleep 1
done

# Ensure buckets exist
mc alias set local http://localhost:9000 minioadmin minioadmin 2>/dev/null || true
mc mb --ignore-existing local/yakread-content 2>/dev/null || true
mc mb --ignore-existing local/yakread-emails 2>/dev/null || true
mc mb --ignore-existing local/yakread-images 2>/dev/null || true
mc mb --ignore-existing local/yakread-export 2>/dev/null || true

echo "=== Creating Tailwind CSS sprite service ==="
sprite-env services create tailwind \
  --cmd "$REPO_DIR/sprite/start-tailwind.sh" \
  --dir "$REPO_DIR" 2>/dev/null || echo "tailwind service may already exist"

# Wait for initial CSS compilation
echo "Waiting for Tailwind CSS to compile..."
for i in $(seq 1 30); do
  if [ -f "$REPO_DIR/target/resources/public/css/main.css" ]; then
    echo "Tailwind CSS compiled successfully"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "WARNING: Tailwind CSS not compiled after 30 seconds"
  fi
  sleep 1
done

echo "=== Creating Yakread app sprite service ==="
sprite-env services create yakread \
  --cmd "$REPO_DIR/sprite/start-app.sh" \
  --dir "$REPO_DIR" \
  --needs minio,tailwind \
  --http-port 8080 \
  --duration 90s

echo "=== Waiting for Yakread server to be ready ==="
for i in $(seq 1 120); do
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/signin 2>/dev/null | grep -q "200"; then
    echo "Yakread server is ready!"
    break
  fi
  if [ "$i" -eq 120 ]; then
    echo "WARNING: Server not ready after 120 seconds"
  fi
  sleep 2
done

echo "=== Setup complete ==="
