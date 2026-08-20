#!/usr/bin/env bash
set -euo pipefail

# Descriptor-level additive compatibility and checked-in generated-source drift.

repo_root="$(git rev-parse --show-toplevel)"
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
  echo "no protobuf base ref available; refusing to skip compatibility validation"
  exit 2
fi

for tool in protoc protoc-gen-go protoc-gen-go-grpc go; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "required protobuf governance tool is unavailable: $tool"
    exit 2
  fi
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/reef-proto-governance.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
mkdir -p "$work_dir/base" "$work_dir/generated-go" "$work_dir/generated-java"

git archive "$base_ref" contracts/proto | tar -x -C "$work_dir/base"

baseline_protos=()
while IFS= read -r file; do
  baseline_protos+=("$file")
done < <(find "$work_dir/base/contracts/proto" -name '*.proto' -type f | sort)
current_protos=()
while IFS= read -r file; do
  current_protos+=("$file")
done < <(find "$repo_root/contracts/proto" -name '*.proto' -type f | sort)
if [[ ${#baseline_protos[@]} -eq 0 || ${#current_protos[@]} -eq 0 ]]; then
  echo "protobuf contract set is empty"
  exit 2
fi

protoc -I "$work_dir/base" --include_imports --descriptor_set_out="$work_dir/baseline.pb" "${baseline_protos[@]}"
protoc -I "$repo_root" --include_imports --descriptor_set_out="$work_dir/current.pb" "${current_protos[@]}"

(
  cd "$repo_root/services/matching-engine"
  GOCACHE="${GOCACHE:-$work_dir/go-cache}" go run ./cmd/proto-compat-check \
    --baseline "$work_dir/baseline.pb" --current "$work_dir/current.pb"
)

PROTO_GO_OUT="$work_dir/generated-go" \
PROTO_JAVA_OUT="$work_dir/generated-java" \
  "$repo_root/scripts/generate-proto.sh"

status=0
if ! diff -ru \
  "$repo_root/services/matching-engine/internal/transport/grpc/pb/contracts/proto" \
  "$work_dir/generated-go/contracts/proto"; then
  echo "checked-in Go protobuf sources are stale; regenerate them with protoc 33.2, protoc-gen-go v1.34.2, and protoc-gen-go-grpc 1.5.1"
  status=1
fi
if ! diff -ru \
  "$repo_root/services/platform-runtime/src/main/java/reef/contracts/orderexecution/v1" \
  "$work_dir/generated-java/reef/contracts/orderexecution/v1"; then
  echo "checked-in Java protobuf sources are stale; regenerate them with protoc 33.2"
  status=1
fi

if [[ $status -ne 0 ]]; then
  exit "$status"
fi

echo "Protobuf generated-source drift check passed."
