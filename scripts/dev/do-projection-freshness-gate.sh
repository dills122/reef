#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'USAGE'
usage: scripts/dev/do-projection-freshness-gate.sh <plan|run|run-destroy|check>

Runs the named DigitalOcean projection/read-model freshness gate for the
Redpanda direct-stream plus venue-event-materializer path.

optional:
  REEF_DO_PROJECTION_FRESHNESS_GATE_TIER=short|soak-5m|soak-15m
  REEF_DO_CONFIRM_DESTROYABLE=1
  DIGITALOCEAN_TOKEN or DO_TOKEN

defaults:
  short     2.5k rps, 256 workers, 60s, 1 sample, c-16
  soak-5m   2.5k rps, 256 workers, 5m, 1 sample, c-16
  soak-15m  2.5k rps, 256 workers, 15m, 1 sample, c-16

gates:
  accepted rps >= configured target
  attempted rps >= configured target
  accepted/direct-acked/materialized/projected gaps = 0
  projector failed delta = 0
  projector final lag = 0
  direct-stream active partitions >= 16
  direct-stream partition skew <= 4
  DB WAL/activity/settings/pg_stat_io diagnostics present
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

tier="${REEF_DO_PROJECTION_FRESHNESS_GATE_TIER:-short}"
case "$tier" in
  short)
    default_duration="60s"
    ;;
  soak-5m)
    default_duration="5m"
    ;;
  soak-15m)
    default_duration="15m"
    ;;
  *)
    echo "unsupported REEF_DO_PROJECTION_FRESHNESS_GATE_TIER=$tier" >&2
    exit 2
    ;;
esac

export REEF_DO_BENCHMARK_PROFILE="${REEF_DO_BENCHMARK_PROFILE:-materializer-projection}"
export REEF_DO_BENCHMARK_GOAL="${REEF_DO_BENCHMARK_GOAL:-fixed}"
export REEF_DO_STRESS_RATES="${REEF_DO_STRESS_RATES:-2500}"
export REEF_DO_STRESS_WORKERS="${REEF_DO_STRESS_WORKERS:-256}"
export REEF_DO_STRESS_REPEAT_SAMPLES="${REEF_DO_STRESS_REPEAT_SAMPLES:-1}"
export REEF_DO_STRESS_DURATION="${REEF_DO_STRESS_DURATION:-$default_duration}"
export REEF_DO_SIZE="${REEF_DO_SIZE:-c-16}"
export REEF_DO_MIN_ATTEMPTED_RPS="${REEF_DO_MIN_ATTEMPTED_RPS:-2400}"
export REEF_DO_MIN_ACCEPTED_RPS="${REEF_DO_MIN_ACCEPTED_RPS:-2400}"
export REEF_DO_MIN_PROJECTED_RPS="${REEF_DO_MIN_PROJECTED_RPS:-2400}"
export REEF_DO_MAX_PROJECTION_LAG="${REEF_DO_MAX_PROJECTION_LAG:-0}"
export REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP="${REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP:-0}"
export REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS="${REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS:-16}"
export REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW="${REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW:-4}"
export REEF_DO_REQUIRE_DB_DIAGNOSTICS="${REEF_DO_REQUIRE_DB_DIAGNOSTICS:-1}"
export REEF_DO_REQUIRE_PG_STAT_IO="${REEF_DO_REQUIRE_PG_STAT_IO:-1}"
export STREAM_ACK_PROJECTOR_0_PARTITIONS="${STREAM_ACK_PROJECTOR_0_PARTITIONS:-0,1,2,3}"
export STREAM_ACK_PROJECTOR_1_PARTITIONS="${STREAM_ACK_PROJECTOR_1_PARTITIONS:-4,5,6,7}"
export STREAM_ACK_PROJECTOR_2_PARTITIONS="${STREAM_ACK_PROJECTOR_2_PARTITIONS:-8,9,10,11}"
export STREAM_ACK_PROJECTOR_3_PARTITIONS="${STREAM_ACK_PROJECTOR_3_PARTITIONS:-12,13,14,15}"

cd "$ROOT_DIR"
exec scripts/dev/do-benchmark-host.sh "$host_command"
