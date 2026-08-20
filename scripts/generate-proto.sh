#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
go_out="${PROTO_GO_OUT:-$repo_root/services/matching-engine/internal/transport/grpc/pb}"
java_out="${PROTO_JAVA_OUT:-$repo_root/services/platform-runtime/src/main/java}"

for tool in protoc protoc-gen-go protoc-gen-go-grpc perl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "required protobuf generation tool is unavailable: $tool"
    exit 2
  fi
done

mkdir -p "$go_out" "$java_out"
(
  cd "$repo_root"
  protoc -I . \
    --go_out="$go_out" --go_opt=paths=source_relative \
    --go-grpc_out="$go_out" --go-grpc_opt=paths=source_relative \
    contracts/proto/*.proto
  protoc -I . --java_out="$java_out" contracts/proto/*.proto
)

# The Java generator emits spaces at line endings. Normalize generated files
# so repository diffs and drift checks remain clean and reproducible.
find "$java_out/reef/contracts" -name '*.java' -type f -exec perl -pi -e 's/[ \t]+$//' {} +
find "$java_out/reef/contracts" -name '*.java' -type f -exec perl -0777 -pi -e 's/\s+\z/\n/' {} +
