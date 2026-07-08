#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'USAGE'
usage: scripts/dev/do-materializer-10k-gate.sh <plan|run|run-destroy|check>

Runs the named DigitalOcean 10k durable-canonical materializer promotion gate.

optional:
  REEF_DO_MATERIALIZER_10K_GATE_TIER=short|soak-5m|soak-15m
  REEF_DO_CONFIRM_DESTROYABLE=1
  DIGITALOCEAN_TOKEN or DO_TOKEN

defaults:
  short     10k rps, 1024 workers, 60s, 3 samples, c-16
  soak-5m   10k rps, 1024 workers, 5m, 2 samples, c-16
  soak-15m  10k rps, 1024 workers, 15m, 1 sample, c-16

gates:
  accepted rps >= 9900
  attempted rps >= 9900
  p95 <= 100ms
  p99 <= 200ms
  direct-stream active partitions >= 16
  direct-stream partition skew <= 4
  DB WAL/activity/settings/pg_stat_io diagnostics present
  accepted/direct-acked/materialized gaps = 0
USAGE
}

command="${1:-}"
case "$command" in
  plan) host_command="plan-goal" ;;
  run|run-destroy|check) host_command="$command" ;;
  ""|-h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

tier="${REEF_DO_MATERIALIZER_10K_GATE_TIER:-short}"
case "$tier" in
  short)
    default_duration="60s"
    default_repeat_samples="3"
    ;;
  soak-5m)
    default_duration="5m"
    default_repeat_samples="2"
    ;;
  soak-15m)
    default_duration="15m"
    default_repeat_samples="1"
    ;;
  *)
    echo "unsupported REEF_DO_MATERIALIZER_10K_GATE_TIER=$tier" >&2
    exit 2
    ;;
esac

export REEF_DO_BENCHMARK_PROFILE="${REEF_DO_BENCHMARK_PROFILE:-materializer}"
export REEF_DO_BENCHMARK_GOAL="${REEF_DO_BENCHMARK_GOAL:-sustain}"
export REEF_DO_TARGET_ACCEPTED_RPS="${REEF_DO_TARGET_ACCEPTED_RPS:-10000}"
export REEF_DO_TARGET_P95_MS="${REEF_DO_TARGET_P95_MS:-100}"
export REEF_DO_TARGET_P99_MS="${REEF_DO_TARGET_P99_MS:-200}"
export REEF_DO_STRESS_WORKERS="${REEF_DO_STRESS_WORKERS:-1024}"
export REEF_DO_STRESS_REPEAT_SAMPLES="${REEF_DO_STRESS_REPEAT_SAMPLES:-$default_repeat_samples}"
export REEF_DO_STRESS_DURATION="${REEF_DO_STRESS_DURATION:-$default_duration}"
export REEF_DO_SIZE="${REEF_DO_SIZE:-c-16}"
export REEF_DO_MIN_ATTEMPTED_RPS="${REEF_DO_MIN_ATTEMPTED_RPS:-9900}"
export REEF_DO_MIN_ACCEPTED_RPS="${REEF_DO_MIN_ACCEPTED_RPS:-9900}"
export REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS="${REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS:-16}"
export REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW="${REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW:-4}"
export REEF_DO_REQUIRE_DB_DIAGNOSTICS="${REEF_DO_REQUIRE_DB_DIAGNOSTICS:-1}"
export REEF_DO_REQUIRE_PG_STAT_IO="${REEF_DO_REQUIRE_PG_STAT_IO:-1}"

cd "$ROOT_DIR"
exec scripts/dev/do-benchmark-host.sh "$host_command"
