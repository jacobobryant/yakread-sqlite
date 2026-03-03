#!/usr/bin/env bash
export JAVA_HOME=/usr/lib/jvm/jdk-21.0.10+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/sprite/repo
exec clojure -M:dev-server
