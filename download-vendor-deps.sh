#!/usr/bin/env bash
# Downloads vendored JS dependencies into resources/public/vendor/.
# The paths mirror the CDN URLs, matching the references in shell.clj.
set -euo pipefail

VENDOR_DIR="resources/public/vendor"

download() {
  local path="$1"
  local url="$2"
  mkdir -p "$(dirname "$VENDOR_DIR/$path")"
  echo "Downloading $path ..."
  curl -fL --retry 2 -o "$VENDOR_DIR/$path" "$url"
}

download "cdn.jsdelivr.net/npm/htmx.org@2.0.5/dist/htmx.min.js" \
         "https://cdn.jsdelivr.net/npm/htmx.org@2.0.5/dist/htmx.min.js"

download "unpkg.com/idiomorph@0.7.3.js" \
         "https://unpkg.com/idiomorph@0.7.3"

download "unpkg.com/idiomorph@0.7.3/dist/idiomorph-ext.min.js" \
         "https://unpkg.com/idiomorph@0.7.3/dist/idiomorph-ext.min.js"

download "unpkg.com/hyperscript.org@0.9.14.js" \
         "https://unpkg.com/hyperscript.org@0.9.14"

download "cdnjs.cloudflare.com/ajax/libs/dompurify/3.2.6/purify.min.js" \
         "https://cdnjs.cloudflare.com/ajax/libs/dompurify/3.2.6/purify.min.js"

download "cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-beta.9/bundles/datastar.js" \
         "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-beta.9/bundles/datastar.js"

echo "All vendor dependencies downloaded."
