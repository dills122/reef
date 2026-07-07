#!/usr/bin/env bash
set -euo pipefail

# Additive-first guard: fail when a numbered protobuf field is removed.
# This compares current branch against PROTO_BASE_REF, defaulting to origin/HEAD.

base_ref="${PROTO_BASE_REF:-origin/HEAD}"

if ! git rev-parse --verify "$base_ref" >/dev/null 2>&1; then
  for candidate in origin/main origin/master; do
    if git rev-parse --verify "$candidate" >/dev/null 2>&1; then
      base_ref="$candidate"
      break
    fi
  done
fi

if ! git rev-parse --verify "$base_ref" >/dev/null 2>&1; then
  echo "no proto base ref available; skipping proto additive check"
  exit 0
fi

status=0
while IFS= read -r file; do
  [[ -n "$file" ]] || continue
  removed=$(git diff --unified=0 "$base_ref"...HEAD -- "$file" | grep -E '^-.*= [0-9]+;' || true)
  if [[ -n "$removed" ]]; then
    echo "Proto compatibility violation in $file:"
    echo "$removed"
    status=1
  fi
done < <(git diff --name-only "$base_ref"...HEAD -- contracts/proto/*.proto)

if [[ $status -ne 0 ]]; then
  echo "Detected removed protobuf fields. Policy is additive-first."
  exit $status
fi

echo "Proto additive check passed."
