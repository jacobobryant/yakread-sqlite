#!/bin/bash
# Shared script to install sqlite3def for CI workflows.
# Used by both copilot-setup-steps.yml and playwright-tests.yml.
set -e

SQLDEF_VERSION="v3.9.7"

curl -L -o /tmp/sqlite3def.tar.gz "https://github.com/sqldef/sqldef/releases/download/${SQLDEF_VERSION}/sqlite3def_linux_amd64.tar.gz"
tar -xzf /tmp/sqlite3def.tar.gz -C /usr/local/bin sqlite3def
chmod +x /usr/local/bin/sqlite3def
echo "sqlite3def installed: $(sqlite3def --version 2>&1 || echo 'ok')"
