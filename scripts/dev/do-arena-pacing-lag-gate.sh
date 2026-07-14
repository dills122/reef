#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'USAGE'
usage: scripts/dev/do-arena-pacing-lag-gate.sh <plan|run|run-destroy>

Runs the short DigitalOcean Bot Arena pacing-lag cleanup gate.

optional:
  REEF_DO_ARENA_PACING_DURATIONS=300s,300s,450s
  REEF_DO_ARENA_MAX_FINAL_COMPLETION_LAG_MS=30000
  REEF_DO_CONFIRM_DESTROYABLE=1
  DIGITALOCEAN_TOKEN or DO_TOKEN

defaults:
  durations: 5m, 5m, 7.5m
  profile: arena
  size: c-8
  command wait mode: accepted
  projection drain cadence: scheduled-event
  command/projection drain poll: 100ms

gate:
  each run must pass the arena artifact checker, hardening summary, command
  accounting, health gate, projection drain, and finalCompletionLagMs target.
USAGE
}

command="${1:-}"
case "$command" in
  plan|run|run-destroy) ;;
  ""|-h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

durations="${REEF_DO_ARENA_PACING_DURATIONS:-300s,300s,450s}"

export REEF_DO_BENCHMARK_PROFILE="${REEF_DO_BENCHMARK_PROFILE:-arena}"
export REEF_DO_SIZE="${REEF_DO_SIZE:-c-8}"
export REEF_DO_IMAGE_MODE="${REEF_DO_IMAGE_MODE:-source}"
export REEF_DO_ARENA_SCORING_POLICY_VERSION="${REEF_DO_ARENA_SCORING_POLICY_VERSION:-score-v1}"
export REEF_DO_ARENA_COMMAND_WAIT_MODE="${REEF_DO_ARENA_COMMAND_WAIT_MODE:-accepted}"
export REEF_DO_ARENA_COMMAND_POLL_MS="${REEF_DO_ARENA_COMMAND_POLL_MS:-100}"
export REEF_DO_ARENA_PROJECTION_DRAIN_POLL_MS="${REEF_DO_ARENA_PROJECTION_DRAIN_POLL_MS:-100}"
export REEF_DO_ARENA_PROJECTION_DRAIN_TIMEOUT_MS="${REEF_DO_ARENA_PROJECTION_DRAIN_TIMEOUT_MS:-30000}"
export REEF_DO_ARENA_PROJECTION_DRAIN_CADENCE="${REEF_DO_ARENA_PROJECTION_DRAIN_CADENCE:-scheduled-event}"
export REEF_DO_ARENA_REQUIRE_HEALTH_PASS="${REEF_DO_ARENA_REQUIRE_HEALTH_PASS:-1}"
export REEF_DO_ARENA_MAX_FINAL_COMPLETION_LAG_MS="${REEF_DO_ARENA_MAX_FINAL_COMPLETION_LAG_MS:-30000}"

IFS=',' read -ra duration_values <<< "$durations"
cd "$ROOT_DIR"

for index in "${!duration_values[@]}"; do
  duration="${duration_values[$index]}"
  duration="${duration#"${duration%%[![:space:]]*}"}"
  duration="${duration%"${duration##*[![:space:]]}"}"
  if [ -z "$duration" ]; then
    echo "empty duration in REEF_DO_ARENA_PACING_DURATIONS=$durations" >&2
    exit 2
  fi

  export REEF_DO_STRESS_DURATION="$duration"
  echo "arena pacing sample $((index + 1))/${#duration_values[@]} duration=$duration command=$command"
  if [ "$command" = "plan" ]; then
    scripts/dev/do-benchmark-host.sh plan-goal
  else
    scripts/dev/do-benchmark-host.sh "$command"
  fi
done
