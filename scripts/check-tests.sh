#!/usr/bin/env bash
# Run unit tests and fail if any test results have changed.
# Used as a pre-commit hook and in CI.
set -euo pipefail

echo "Running unit tests..."
clojure -X:run com.yakread.lib.test/run-examples!

# Check if any test files have meaningful changes (ignore stack trace noise).
# Stack traces contain randomly-generated temp namespace names (e.g. tmp923762)
# that change every run, so we exclude those from the diff.
changed=$(git diff -- 'test/**/*_test.edn' | grep '^[+-]' | grep -v '^[+-][+-][+-]' | grep -v 'tmp[0-9]*\$' || true)
if [ -n "$changed" ]; then
  echo ""
  echo "ERROR: Test results have changed:"
  git diff --stat -- 'test/**/*_test.edn'
  echo ""
  echo "Review the changes with 'git diff test/' and commit them if correct."
  exit 1
fi

echo "All test results are up to date."
