#!/usr/bin/env bash
set -euo pipefail

# Additive-first guard: fail when a numbered protobuf field is removed.
# This compares current branch against origin/main for contracts/proto/*.proto.

if ! git rev-parse --verify origin/main >/dev/null 2>&1; then
  echo "origin/main not available; skipping proto additive check"
  exit 0
fi

status=0
for file in $(git diff --name-only origin/main...HEAD -- contracts/proto/*.proto); do
  removed=$(git diff --unified=0 origin/main...HEAD -- "$file" | grep -E '^-.*= [0-9]+;' || true)
  if [[ -n "$removed" ]]; then
    echo "Proto compatibility violation in $file:"
    echo "$removed"
    status=1
  fi
done

if [[ $status -ne 0 ]]; then
  echo "Detected removed protobuf fields. Policy is additive-first."
  exit $status
fi

echo "Proto additive check passed."
