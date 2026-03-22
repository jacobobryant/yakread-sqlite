#!/usr/bin/env bash
set -e
cd /home/sprite/repo

export JAVA_HOME=/usr/lib/jvm/jdk-21.0.10+7
export PATH=$JAVA_HOME/bin:$PATH

# Export all vars from config.env
set -a
source config.env
set +a
export BIFF_PROFILE=dev

exec clojure -M:dev-server
