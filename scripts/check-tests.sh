#!/usr/bin/env bash
# Run unit tests and fail if any test results have changed.
# Used as a pre-commit hook and in CI.
set -euo pipefail

echo "Running unit tests..."
clojure -X:run com.yakread.lib.test/run-examples!

# Check if any test files have changed
changed=$(git diff --name-only -- 'test/**/*_test.edn')
if [ -n "$changed" ]; then
  echo ""
  echo "ERROR: Test results have changed:"
  echo "$changed"
  echo ""
  echo "Review the changes with 'git diff test/' and commit them if correct."
  exit 1
fi

echo "All test results are up to date."
