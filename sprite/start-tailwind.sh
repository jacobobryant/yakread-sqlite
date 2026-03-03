#!/usr/bin/env bash
cd /home/sprite/repo
mkdir -p target/resources/public/css
# Keep stdin open with a pipe so --watch doesn't exit in service mode
tail -f /dev/null | exec tailwindcss \
  -c resources/tailwind.config.js \
  -i resources/tailwind.css \
  -o target/resources/public/css/main.css \
  --minify \
  --watch \
  --poll
