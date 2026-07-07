#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MATCHING_DIR="${MATCHING_ENGINE_DIR:-$ROOT_DIR/services/matching-engine}"
BENCH_REGEX="${BENCH_REGEX:-BenchmarkSubmitOrderResting|BenchmarkSubmitOrderMatchAgainstResting|BenchmarkModifyOrder}"

LIMIT_SUBMIT_NS="${LIMIT_SUBMIT_NS:-5000}"
LIMIT_SUBMIT_ALLOCS="${LIMIT_SUBMIT_ALLOCS:-24}"
LIMIT_MATCH_NS="${LIMIT_MATCH_NS:-10000}"
LIMIT_MATCH_ALLOCS="${LIMIT_MATCH_ALLOCS:-48}"
LIMIT_MODIFY_NS="${LIMIT_MODIFY_NS:-300000}"
LIMIT_MODIFY_ALLOCS="${LIMIT_MODIFY_ALLOCS:-16}"

TMP_OUT="$(mktemp)"
trap 'rm -f "$TMP_OUT"' EXIT

echo "Running matching-engine benchmark guardrail..."
echo "Directory : $MATCHING_DIR"
echo "Bench regex: $BENCH_REGEX"

(
  cd "$MATCHING_DIR"
  go test -run '^$' -bench "$BENCH_REGEX" -benchmem ./internal/app
) | tee "$TMP_OUT"

extract_bench_line() {
  local bench="$1"
  grep -E "^${bench}-" "$TMP_OUT" | tail -n 1 || true
}

extract_col() {
  local line="$1"
  local col="$2"
  awk -v c="$col" '{print $c}' <<<"$line"
}

failed=0

check_leq() {
  local metric="$1"
  local value="$2"
  local limit="$3"
  local bench="$4"
  if ! awk -v v="$value" -v l="$limit" 'BEGIN { exit !(v <= l) }'; then
    echo "FAIL: $bench $metric=$value exceeds limit=$limit" >&2
    failed=1
  fi
}

check_benchmark() {
  local bench="$1"
  local ns_limit="$2"
  local alloc_limit="$3"

  local line
  line="$(extract_bench_line "$bench")"
  if [[ -z "$line" ]]; then
    echo "FAIL: benchmark output missing for $bench" >&2
    failed=1
    return
  fi

  local ns allocs
  ns="$(extract_col "$line" 3)"
  allocs="$(extract_col "$line" 7)"

  echo "$bench -> ns/op=$ns allocs/op=$allocs (limits: ns/op<=$ns_limit allocs/op<=$alloc_limit)"

  check_leq "ns/op" "$ns" "$ns_limit" "$bench"
  check_leq "allocs/op" "$allocs" "$alloc_limit" "$bench"
}

check_benchmark "BenchmarkSubmitOrderResting" "$LIMIT_SUBMIT_NS" "$LIMIT_SUBMIT_ALLOCS"
check_benchmark "BenchmarkSubmitOrderMatchAgainstResting" "$LIMIT_MATCH_NS" "$LIMIT_MATCH_ALLOCS"
check_benchmark "BenchmarkModifyOrder" "$LIMIT_MODIFY_NS" "$LIMIT_MODIFY_ALLOCS"

if [[ "$failed" -ne 0 ]]; then
  echo "Benchmark guardrail failed." >&2
  exit 1
fi

echo "Benchmark guardrail passed."
