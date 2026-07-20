#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'USAGE'
usage: scripts/dev/do-materializer-scaling-gate.sh <plan|run|run-destroy|check>

Runs the named DigitalOcean durable-canonical scaling gate.

optional:
  REEF_DO_MATERIALIZER_SCALING_TIER=knee|20k-short|20k-soak-5m
  REEF_DO_CONFIRM_DESTROYABLE=1
  DIGITALOCEAN_TOKEN or DO_TOKEN

defaults:
  knee         10k,12.5k,15k,17.5k,20k,25k; 60s each; c-16
  20k-short    20k rps; 60s; 3 samples; c-16
  20k-soak-5m  20k rps; 5m; 2 samples; c-16

All materializer checks require exact accepted/published/direct-acked/
materialized accounting, zero processing failures, DB diagnostics, all 16
partitions active, p95 <= 100ms, and p99 <= 200ms.
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

tier="${REEF_DO_MATERIALIZER_SCALING_TIER:-knee}"
case "$tier" in
  knee)
    default_goal="ceiling"
    default_rates="10000,12500,15000,17500,20000,25000"
    default_duration="60s"
    default_repeat_samples="1"
    default_min_rps="9900"
    ;;
  20k-short)
    default_goal="sustain"
    default_rates="20000"
    default_duration="60s"
    default_repeat_samples="3"
    default_min_rps="19800"
    ;;
  20k-soak-5m)
    default_goal="sustain"
    default_rates="20000"
    default_duration="5m"
    default_repeat_samples="2"
    default_min_rps="19800"
    ;;
  *)
    echo "unsupported REEF_DO_MATERIALIZER_SCALING_TIER=$tier" >&2
    exit 2
    ;;
esac

export REEF_DO_BENCHMARK_PROFILE="${REEF_DO_BENCHMARK_PROFILE:-materializer}"
export REEF_DO_BENCHMARK_GOAL="${REEF_DO_BENCHMARK_GOAL:-$default_goal}"
export REEF_DO_TARGET_ACCEPTED_RPS="${REEF_DO_TARGET_ACCEPTED_RPS:-20000}"
export REEF_DO_TARGET_P95_MS="${REEF_DO_TARGET_P95_MS:-100}"
export REEF_DO_TARGET_P99_MS="${REEF_DO_TARGET_P99_MS:-200}"
export REEF_DO_STRESS_RATES="${REEF_DO_STRESS_RATES:-$default_rates}"
export REEF_DO_STRESS_WORKERS="${REEF_DO_STRESS_WORKERS:-2048}"
export REEF_DO_STRESS_REPEAT_SAMPLES="${REEF_DO_STRESS_REPEAT_SAMPLES:-$default_repeat_samples}"
export REEF_DO_STRESS_DURATION="${REEF_DO_STRESS_DURATION:-$default_duration}"
export REEF_DO_SIZE="${REEF_DO_SIZE:-c-16}"
export REEF_DO_MIN_ATTEMPTED_RPS="${REEF_DO_MIN_ATTEMPTED_RPS:-$default_min_rps}"
export REEF_DO_MIN_ACCEPTED_RPS="${REEF_DO_MIN_ACCEPTED_RPS:-$default_min_rps}"
export REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS="${REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS:-16}"
export REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW="${REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW:-4}"
export REEF_DO_REQUIRE_DB_DIAGNOSTICS="${REEF_DO_REQUIRE_DB_DIAGNOSTICS:-1}"
export REEF_DO_REQUIRE_PG_STAT_IO="${REEF_DO_REQUIRE_PG_STAT_IO:-1}"

cd "$ROOT_DIR"
exec scripts/dev/do-benchmark-host.sh "$host_command"
