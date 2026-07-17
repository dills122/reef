#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INFRA_DIR="$ROOT_DIR/infra/do-benchmark"
LOCAL_REPORT_ROOT="${REEF_DO_LOCAL_REPORT_ROOT:-$ROOT_DIR/reports/do-benchmark}"
REMOTE_DIR=""
REMOTE_ARTIFACT_ROOT=""

load_env_file() {
  local path="$1"
  [ -f "$path" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" == *"="* ]] || continue
    local key="${line%%=*}"
    local value="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ "$value" == \"*\" && "$value" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi
    if [ -z "${!key+x}" ]; then
      export "$key=$value"
    fi
  done < "$path"
}

usage() {
  cat <<'USAGE'
usage: scripts/dev/do-benchmark-host.sh <command>

commands:
  up             provision the DO benchmark droplet and firewall
  plan-goal      print resolved profile, sizing, load, and report gates without provisioning
  status         print OpenTofu outputs
  sync           rsync the current checkout to the droplet
  start          start the remote stream-ack stack
  run            provision, sync, run the benchmark, fetch artifacts, and check reports
  check          check fetched benchmark artifacts without touching DO resources
  remote-status  show remote host and compose status
  reset-remote   stop remote compose, remove volumes, and wipe remote benchmark artifacts
  logs           fetch recent remote compose logs to stdout
  export         compress the run's artifacts on the worker and upload to R2, before fetch/destroy
  fetch          fetch /tmp/reef-do-benchmark artifacts into reports/do-benchmark
  fetch-destroy  fetch artifacts, then destroy the droplet
  run-destroy    provision, run benchmark, fetch artifacts, check reports, then destroy
  destroy        destroy the DO benchmark resources

required for provisioning:
  DIGITALOCEAN_TOKEN or DO_TOKEN
  REEF_DO_CONFIRM_DESTROYABLE=1

optional:
  REEF_DO_SSH_PUBLIC_KEY=~/.ssh/id_ed25519.pub
  REEF_DO_SSH_PRIVATE_KEY=~/.ssh/id_ed25519
  REEF_DO_ALLOWED_SSH_CIDRS=203.0.113.10/32
  REEF_DO_REGION=sfo2
  REEF_DO_SIZE=c-8
  REEF_DO_BENCHMARK_PROFILE=stream-ack|materializer|materializer-projection|arena
  REEF_DO_BENCHMARK_GOAL=fixed|latency-knee|sustain|ceiling
  REEF_DO_TARGET_ACCEPTED_RPS=10000
  REEF_DO_TARGET_P95_MS=100
  REEF_DO_TARGET_P99_MS=200
  REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS=16
  REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW=4
  REEF_DO_STRESS_RATES=2500,5000
  REEF_DO_STRESS_WORKERS=256
  REEF_DO_STRESS_REPEAT_SAMPLES=1
  REEF_DO_STRESS_DURATION=30s
  REEF_DO_DRAIN_BACKPRESSURE_POLICY=control-room-fresh
  REEF_DO_REQUIRE_DB_DIAGNOSTICS=1
  REEF_DO_REQUIRE_PG_STAT_IO=1
  REEF_DO_MAX_PROJECTION_DB_RETRIES=0
  REEF_DO_PROJECTION_STAGE=full|command-status|timeline
  REEF_DO_IMAGE_MODE=dockerhub|source
  REEF_DO_STAGE_LOG_TAIL=80

optional R2 artifact export (compressed debug data, uploaded from the worker
before fetch/destroy, reusing the backbone's Postgres/OpenBao backup R2
bucket credentials - see D-046):
  REEF_DO_EXPORT_TO_R2=1
  R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
  R2_BUCKET=reef-backups
  AWS_ACCESS_KEY_ID=...
  AWS_SECRET_ACCESS_KEY=...
USAGE
}

main() {
  cd "$ROOT_DIR"
  log "loading local environment"
  load_env_file "$ROOT_DIR/.env"
  load_env_file "$ROOT_DIR/.env.local"
  refresh_runtime_config

  local command="${1:-}"
  case "$command" in
    up) cmd_up ;;
    plan-goal) cmd_plan_goal ;;
    status) cmd_status ;;
    sync) cmd_sync ;;
    start) cmd_start ;;
    run) cmd_run ;;
    check) cmd_check ;;
    remote-status) cmd_remote_status ;;
    reset-remote) cmd_reset_remote ;;
    logs) cmd_logs ;;
    export) cmd_export ;;
    fetch) cmd_fetch ;;
    fetch-destroy) cmd_fetch_destroy ;;
    run-destroy) cmd_run_destroy ;;
    destroy) cmd_destroy ;;
    ""|-h|--help|help) usage ;;
    *) usage; exit 2 ;;
  esac
}

cmd_up() {
  provision_stack
}

cmd_plan_goal() {
  local profile goal target rates workers repeat_samples duration trace_limit size min_rps max_p95 max_p99
  profile="$(benchmark_profile)"
  goal="$(benchmark_goal)"
  target="$(benchmark_target_rps "$profile")"
  rates="${REEF_DO_STRESS_RATES:-$(benchmark_default_rates "$profile")}"
  workers="${REEF_DO_STRESS_WORKERS:-$(benchmark_default_workers "$profile")}"
  repeat_samples="${REEF_DO_STRESS_REPEAT_SAMPLES:-1}"
  duration="${REEF_DO_STRESS_DURATION:-$(benchmark_default_duration "$profile")}"
  trace_limit="${REEF_DO_TRACE_CHECK_LIMIT:-$(benchmark_default_trace_limit "$profile")}"
  size="${REEF_DO_SIZE:-$(benchmark_default_size "$profile")}"
  min_rps="${REEF_DO_MIN_ACCEPTED_RPS:-$(benchmark_default_min_rps "$profile")}"
  max_p95="${REEF_DO_MAX_P95_MS:-${REEF_DO_TARGET_P95_MS:-}}"
  max_p99="${REEF_DO_MAX_P99_MS:-${REEF_DO_TARGET_P99_MS:-}}"

  printf 'DO benchmark goal plan:\n'
  printf '  profile=%s\n' "$profile"
  printf '  goal=%s\n' "$goal"
  printf '  target_accepted_rps=%s\n' "${target:-none}"
  printf '  region=%s\n' "${REEF_DO_REGION:-sfo2}"
  printf '  size=%s\n' "$size"
  printf '  rates=%s\n' "$rates"
  printf '  workers=%s\n' "$workers"
  printf '  repeat_samples=%s\n' "$repeat_samples"
  printf '  duration=%s\n' "$duration"
  printf '  trace_check_limit=%s\n' "$trace_limit"
  printf '  min_attempted_rps=%s\n' "${REEF_DO_MIN_ATTEMPTED_RPS:-$min_rps}"
  printf '  min_accepted_rps=%s\n' "${REEF_DO_MIN_ACCEPTED_RPS:-$min_rps}"
  printf '  min_projected_rps=%s\n' "${REEF_DO_MIN_PROJECTED_RPS:-none}"
  printf '  max_projection_lag=%s\n' "${REEF_DO_MAX_PROJECTION_LAG:-none}"
  printf '  max_materialized_to_projected_gap=%s\n' "${REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP:-none}"
  printf '  max_projection_db_deadlocks=%s\n' "${REEF_DO_MAX_PROJECTION_DB_DEADLOCKS:-none}"
  printf '  max_projection_db_retries=%s\n' "${REEF_DO_MAX_PROJECTION_DB_RETRIES:-none}"
  printf '  projection_stage=%s\n' "${REEF_DO_PROJECTION_STAGE:-full}"
  printf '  stream_ack_projector_0_partitions=%s\n' "${STREAM_ACK_PROJECTOR_0_PARTITIONS:-none}"
  printf '  stream_ack_projector_1_partitions=%s\n' "${STREAM_ACK_PROJECTOR_1_PARTITIONS:-none}"
  printf '  stream_ack_projector_2_partitions=%s\n' "${STREAM_ACK_PROJECTOR_2_PARTITIONS:-none}"
  printf '  stream_ack_projector_3_partitions=%s\n' "${STREAM_ACK_PROJECTOR_3_PARTITIONS:-none}"
  printf '  max_p95_ms=%s\n' "${max_p95:-none}"
  printf '  max_p99_ms=%s\n' "${max_p99:-none}"
  printf '  min_stream_direct_active_partitions=%s\n' "${REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS:-none}"
  printf '  max_stream_direct_partition_skew=%s\n' "${REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW:-none}"
  printf '  require_db_diagnostics=%s\n' "${REEF_DO_REQUIRE_DB_DIAGNOSTICS:-0}"
  printf '  require_pg_stat_io=%s\n' "${REEF_DO_REQUIRE_PG_STAT_IO:-0}"
  printf '  image_mode=%s\n' "${REEF_DO_IMAGE_MODE:-source}"
}

cmd_status() {
  configure_tf_vars optional
  tofu output
}

cmd_sync() {
  configure_tf_vars optional
  wait_for_ssh
  sync_repo
}

cmd_start() {
  configure_tf_vars optional
  wait_for_ssh
  remote_start_stack
}

cmd_run() {
  provision_stack
  sync_repo
  local run_id
  run_id="$(benchmark_run_id)"
  local status=0
  remote_run_benchmark "$run_id" || status=$?
  remote_collect_artifacts "$run_id" || status=$?
  if [ "${REEF_DO_EXPORT_TO_R2:-0}" = "1" ]; then
    remote_export_to_r2 "$run_id" || status=$?
  fi
  fetch_artifacts || status=$?
  local profile
  profile="$(benchmark_profile)"
  REEF_DO_REPORT_PROFILE="$profile" \
  REEF_DO_REQUIRED_RATES="${REEF_DO_REQUIRED_RATES:-${REEF_DO_STRESS_RATES:-$(benchmark_default_rates "$profile")}}" \
  REEF_DO_MIN_ATTEMPTED_RPS="${REEF_DO_MIN_ATTEMPTED_RPS:-$(benchmark_default_min_rps "$profile")}" \
  REEF_DO_MIN_ACCEPTED_RPS="${REEF_DO_MIN_ACCEPTED_RPS:-$(benchmark_default_min_rps "$profile")}" \
  REEF_DO_MIN_PROJECTED_RPS="${REEF_DO_MIN_PROJECTED_RPS:-}" \
  REEF_DO_MAX_PROJECTION_LAG="${REEF_DO_MAX_PROJECTION_LAG:-}" \
  REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP="${REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP:-}" \
  REEF_DO_MAX_PROJECTION_DB_DEADLOCKS="${REEF_DO_MAX_PROJECTION_DB_DEADLOCKS:-}" \
  REEF_DO_MAX_PROJECTION_DB_RETRIES="${REEF_DO_MAX_PROJECTION_DB_RETRIES:-}" \
  REEF_DO_MAX_P95_MS="${REEF_DO_MAX_P95_MS:-${REEF_DO_TARGET_P95_MS:-}}" \
  REEF_DO_MAX_P99_MS="${REEF_DO_MAX_P99_MS:-${REEF_DO_TARGET_P99_MS:-}}" \
    node scripts/dev/do-benchmark-check.mjs "$LOCAL_REPORT_ROOT/$run_id" || status=$?
  return "$status"
}

cmd_check() {
  local report_dir
  local profile
  profile="$(benchmark_profile)"
  report_dir="$(benchmark_report_dir)"
  REEF_DO_REPORT_PROFILE="$profile" \
  REEF_DO_REQUIRED_RATES="${REEF_DO_REQUIRED_RATES:-${REEF_DO_STRESS_RATES:-$(benchmark_default_rates "$profile")}}" \
  REEF_DO_MIN_ATTEMPTED_RPS="${REEF_DO_MIN_ATTEMPTED_RPS:-$(benchmark_default_min_rps "$profile")}" \
  REEF_DO_MIN_ACCEPTED_RPS="${REEF_DO_MIN_ACCEPTED_RPS:-$(benchmark_default_min_rps "$profile")}" \
  REEF_DO_MIN_PROJECTED_RPS="${REEF_DO_MIN_PROJECTED_RPS:-}" \
  REEF_DO_MAX_PROJECTION_LAG="${REEF_DO_MAX_PROJECTION_LAG:-}" \
  REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP="${REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP:-}" \
  REEF_DO_MAX_PROJECTION_DB_DEADLOCKS="${REEF_DO_MAX_PROJECTION_DB_DEADLOCKS:-}" \
  REEF_DO_MAX_PROJECTION_DB_RETRIES="${REEF_DO_MAX_PROJECTION_DB_RETRIES:-}" \
  REEF_DO_MAX_P95_MS="${REEF_DO_MAX_P95_MS:-${REEF_DO_TARGET_P95_MS:-}}" \
  REEF_DO_MAX_P99_MS="${REEF_DO_MAX_P99_MS:-${REEF_DO_TARGET_P99_MS:-}}" \
    node scripts/dev/do-benchmark-check.mjs "$report_dir"
}

cmd_remote_status() {
  configure_tf_vars optional
  wait_for_ssh
  remote_script <<'REMOTE'
set -euo pipefail
echo "host:"
hostname
uptime
free -h
df -h /
echo
echo "docker compose:"
cd "$REMOTE_DIR"
docker compose ps || true
REMOTE
}

cmd_reset_remote() {
  configure_tf_vars optional
  wait_for_ssh
  remote_script <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
docker compose down --volumes --remove-orphans || true
rm -rf "$REMOTE_ARTIFACT_ROOT"
mkdir -p "$REMOTE_ARTIFACT_ROOT"
echo "remote benchmark Docker volumes and artifacts reset"
REMOTE
}

cmd_logs() {
  configure_tf_vars optional
  wait_for_ssh
  remote_script <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
docker compose logs --no-color --tail="${REEF_DO_LOG_TAIL:-300}" || true
REMOTE
}

cmd_export() {
  configure_tf_vars optional
  wait_for_ssh
  local run_id
  run_id="$(benchmark_run_id)"
  remote_export_to_r2 "$run_id"
}

cmd_fetch() {
  configure_tf_vars optional
  fetch_artifacts
}

cmd_fetch_destroy() {
  local status=0
  configure_tf_vars optional
  fetch_artifacts || status=$?
  cmd_destroy || status=$?
  return "$status"
}

cmd_run_destroy() {
  local status=0
  cmd_run || status=$?
  cmd_destroy || status=$?
  return "$status"
}

cmd_destroy() {
  configure_tf_vars
  tofu destroy -auto-approve
}

provision_stack() {
  require_destroyable_confirmation
  log "configuring OpenTofu variables"
  configure_tf_vars
  log "initializing OpenTofu in $INFRA_DIR"
  tofu init -input=false
  log "applying OpenTofu stack"
  tofu apply -input=false -auto-approve
  log "waiting for benchmark host readiness"
  wait_for_ssh
}

remote_start_stack() {
  local profile
  profile="$(benchmark_profile)"
  remote_script REEF_BENCHMARK_PROFILE="$profile" <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
export JS_RUNTIME=node
export PLATFORM_INTERNAL_HTTP_MODE=enabled
if [ "$REEF_BENCHMARK_PROFILE" = "stream-ack" ]; then
  export STREAM_ACK_COMMAND_STREAM="${STREAM_ACK_COMMAND_STREAM:-REEF_COMMANDS}"
  make dev-up-stream-ack
elif [ "$REEF_BENCHMARK_PROFILE" = "materializer" ] || [ "$REEF_BENCHMARK_PROFILE" = "materializer-projection" ]; then
  export ADMIN_API_TOKEN="${ADMIN_API_TOKEN:-local-admin}"
  export ARENA_ADMIN_API_TOKEN="${ARENA_ADMIN_API_TOKEN:-local-arena}"
  export REEF_ADMIN_API_BEARER_TOKEN="${REEF_ADMIN_API_BEARER_TOKEN:-$ADMIN_API_TOKEN}"
  if [ "$REEF_BENCHMARK_PROFILE" = "materializer-projection" ]; then
    export STREAM_ACK_PROJECTOR_ENABLED=true
    export STREAM_ACK_PROJECTION_SOURCE=venue-event-batch
    export STREAM_ACK_PROJECTOR_INCLUDE_FILLS=true
    export STREAM_ACK_PROJECTION_NAME="${STREAM_ACK_PROJECTION_NAME:-runtime-normalized-venue-outcomes}"
    export STREAM_ACK_PROJECTOR_0_PARTITIONS="${STREAM_ACK_PROJECTOR_0_PARTITIONS:-0,1,2,3}"
    export STREAM_ACK_PROJECTOR_1_PARTITIONS="${STREAM_ACK_PROJECTOR_1_PARTITIONS:-4,5,6,7}"
    export STREAM_ACK_PROJECTOR_2_PARTITIONS="${STREAM_ACK_PROJECTOR_2_PARTITIONS:-8,9,10,11}"
    export STREAM_ACK_PROJECTOR_3_PARTITIONS="${STREAM_ACK_PROJECTOR_3_PARTITIONS:-12,13,14,15}"
    export STREAM_ACK_PROJECTION_STAGE="${STREAM_ACK_PROJECTION_STAGE:-${REEF_DO_PROJECTION_STAGE:-full}}"
    export ORDER_LIFECYCLE_PROJECTOR_ENABLED=true
    export MARKET_DATA_PROJECTOR_ENABLED=true
    export MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME="$STREAM_ACK_PROJECTION_NAME"
  fi
  make dev-smoke-venue-event-materializer
else
  echo "unsupported REEF_DO_BENCHMARK_PROFILE=$REEF_BENCHMARK_PROFILE" >&2
  exit 2
fi
REMOTE
}

remote_run_benchmark() {
  local run_id="$1"
  local profile
  profile="$(benchmark_profile)"
  local stream_name
  stream_name="REEF_COMMANDS_$(sanitize_token "$run_id")"
  local subject_prefix
  subject_prefix="reef.do_benchmark.$(sanitize_token "$run_id")"
  local durable_prefix
  durable_prefix="reef-stream-worker-$(sanitize_token "$run_id")"
  local event_stream
  event_stream="REEF_EVENTS_$(sanitize_token "$run_id")"
  local event_subject_prefix
  event_subject_prefix="reef.do_benchmark.$(sanitize_token "$run_id").events.v1"
  local materializer_group
  materializer_group="reef-venue-event-materializer-$(sanitize_token "$run_id")"
  local rates="${REEF_DO_STRESS_RATES:-$(benchmark_default_rates "$profile")}"
  local workers="${REEF_DO_STRESS_WORKERS:-$(benchmark_default_workers "$profile")}"
  local repeat_samples="${REEF_DO_STRESS_REPEAT_SAMPLES:-1}"
  local duration="${REEF_DO_STRESS_DURATION:-$(benchmark_default_duration "$profile")}"
  local trace_limit="${REEF_DO_TRACE_CHECK_LIMIT:-$(benchmark_default_trace_limit "$profile")}"
  local min_success="${REEF_DO_MIN_SUCCESS_RATE_PCT:-100}"
  local drain_backpressure_policy="${REEF_DO_DRAIN_BACKPRESSURE_POLICY:-${STREAM_ACK_DRAIN_BACKPRESSURE_POLICY:-venue-core}}"
  local image_mode="${REEF_DO_IMAGE_MODE:-source}"

  local arena_duration_seconds="${REEF_DO_ARENA_DURATION_SECONDS:-$(duration_to_seconds "$duration")}"
  local arena_tick_interval_ms="${REEF_DO_ARENA_TICK_INTERVAL_MS:-1000}"
  local arena_warmup_seconds="${REEF_DO_ARENA_WARMUP_SECONDS:-30}"
  local arena_health_sample_interval_ms="${REEF_DO_ARENA_HEALTH_SAMPLE_INTERVAL_MS:-1000}"
  local arena_mode="${REEF_DO_ARENA_MODE:-packages/scenario-definitions/arena/equity-multi-local.v1.json}"
  local arena_scoring_policy_version="${REEF_DO_ARENA_SCORING_POLICY_VERSION:-}"
  local stage_log_tail="${REEF_DO_STAGE_LOG_TAIL:-80}"

  echo "running remote benchmark profile=$profile run_id=$run_id stream=$stream_name subject_prefix=$subject_prefix rates=$rates workers=$workers repeat_samples=$repeat_samples duration=$duration drain_backpressure_policy=$drain_backpressure_policy image_mode=$image_mode"
  remote_script \
    REEF_BENCHMARK_PROFILE="$profile" \
    REEF_BENCHMARK_RUN_ID="$run_id" \
    REEF_BENCHMARK_STREAM="$stream_name" \
    REEF_BENCHMARK_SUBJECT_PREFIX="$subject_prefix" \
    REEF_BENCHMARK_DURABLE_PREFIX="$durable_prefix" \
    REEF_BENCHMARK_EVENT_STREAM="$event_stream" \
    REEF_BENCHMARK_EVENT_SUBJECT_PREFIX="$event_subject_prefix" \
    REEF_BENCHMARK_MATERIALIZER_GROUP="$materializer_group" \
    REEF_BENCHMARK_RATES="$rates" \
    REEF_BENCHMARK_WORKERS="$workers" \
    REEF_BENCHMARK_REPEAT_SAMPLES="$repeat_samples" \
    REEF_BENCHMARK_DURATION="$duration" \
    REEF_BENCHMARK_TRACE_LIMIT="$trace_limit" \
    REEF_BENCHMARK_MIN_SUCCESS="$min_success" \
    REEF_BENCHMARK_DRAIN_BACKPRESSURE_POLICY="$drain_backpressure_policy" \
    REEF_BENCHMARK_ARENA_DURATION_SECONDS="$arena_duration_seconds" \
    REEF_BENCHMARK_ARENA_TICK_INTERVAL_MS="$arena_tick_interval_ms" \
    REEF_BENCHMARK_ARENA_WARMUP_SECONDS="$arena_warmup_seconds" \
    REEF_BENCHMARK_ARENA_HEALTH_SAMPLE_INTERVAL_MS="$arena_health_sample_interval_ms" \
    REEF_BENCHMARK_ARENA_MODE="$arena_mode" \
    REEF_BENCHMARK_ARENA_SCORING_POLICY_VERSION="$arena_scoring_policy_version" \
    REEF_BENCHMARK_STAGE_LOG_TAIL="$stage_log_tail" \
    REEF_BENCHMARK_IMAGE_MODE="$image_mode" \
    STREAM_ACK_PROJECTOR_0_PARTITIONS="${STREAM_ACK_PROJECTOR_0_PARTITIONS:-}" \
    STREAM_ACK_PROJECTOR_1_PARTITIONS="${STREAM_ACK_PROJECTOR_1_PARTITIONS:-}" \
    STREAM_ACK_PROJECTOR_2_PARTITIONS="${STREAM_ACK_PROJECTOR_2_PARTITIONS:-}" \
    STREAM_ACK_PROJECTOR_3_PARTITIONS="${STREAM_ACK_PROJECTOR_3_PARTITIONS:-}" \
    REEF_DO_MAX_PROJECTION_DB_RETRIES="${REEF_DO_MAX_PROJECTION_DB_RETRIES:-}" \
    REEF_DO_PROJECTION_STAGE="${REEF_DO_PROJECTION_STAGE:-}" \
    DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_RETRY_DELTA="${DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_RETRY_DELTA:-}" <<'REMOTE'
set -euo pipefail
artifact_dir="$REMOTE_ARTIFACT_ROOT/$REEF_BENCHMARK_RUN_ID"
log_dir="$artifact_dir/logs"
mkdir -p "$log_dir"
exec > >(tee -a "$log_dir/remote-benchmark.log") 2>&1
cd "$REMOTE_DIR"

echo "[$(date -Is)] remote benchmark starting"
echo "profile=$REEF_BENCHMARK_PROFILE"
echo "run_id=$REEF_BENCHMARK_RUN_ID"
echo "stream=$REEF_BENCHMARK_STREAM"
echo "subject_prefix=$REEF_BENCHMARK_SUBJECT_PREFIX"
echo "durable_prefix=$REEF_BENCHMARK_DURABLE_PREFIX"
echo "event_stream=$REEF_BENCHMARK_EVENT_STREAM"
echo "event_subject_prefix=$REEF_BENCHMARK_EVENT_SUBJECT_PREFIX"
echo "rates=$REEF_BENCHMARK_RATES workers=$REEF_BENCHMARK_WORKERS repeat_samples=$REEF_BENCHMARK_REPEAT_SAMPLES duration=$REEF_BENCHMARK_DURATION"
echo "drain_backpressure_policy=$REEF_BENCHMARK_DRAIN_BACKPRESSURE_POLICY"
echo "image_mode=$REEF_BENCHMARK_IMAGE_MODE"

run_stage() {
  local name="$1"
  shift
  local log_file="$log_dir/stage-${name}.log"
  local started_at
  started_at="$(date +%s)"
  echo "[$(date -Is)] stage: $name (log=$log_file)"
  if "$@" >"$log_file" 2>&1 </dev/null; then
    local finished_at
    finished_at="$(date +%s)"
    echo "[$(date -Is)] stage complete: $name duration_seconds=$((finished_at - started_at))"
    return 0
  fi
  local status=$?
  local finished_at
  finished_at="$(date +%s)"
  echo "[$(date -Is)] stage failed: $name status=$status duration_seconds=$((finished_at - started_at)) log=$log_file" >&2
  echo "[$(date -Is)] tail: $name last ${REEF_BENCHMARK_STAGE_LOG_TAIL:-80} lines" >&2
  tail -n "${REEF_BENCHMARK_STAGE_LOG_TAIL:-80}" "$log_file" >&2 || true
  return "$status"
}

export JS_RUNTIME=node
export PLATFORM_INTERNAL_HTTP_MODE=enabled
export STREAM_ACK_COMMAND_STREAM="$REEF_BENCHMARK_STREAM"
export STREAM_ACK_SUBJECT_PREFIX="$REEF_BENCHMARK_SUBJECT_PREFIX"
export STREAM_ACK_WORKER_DURABLE_PREFIX="$REEF_BENCHMARK_DURABLE_PREFIX"
export STREAM_ACK_DRAIN_BACKPRESSURE_POLICY="$REEF_BENCHMARK_DRAIN_BACKPRESSURE_POLICY"
export DEV_STRESS_ARTIFACT_DIR="$artifact_dir"
export DEV_STRESS_RATES="$REEF_BENCHMARK_RATES"
export DEV_STRESS_SWEEP_WORKERS="$REEF_BENCHMARK_WORKERS"
export DEV_STRESS_REPEAT_SAMPLES="$REEF_BENCHMARK_REPEAT_SAMPLES"
export DEV_STRESS_DURATION="$REEF_BENCHMARK_DURATION"
export DEV_STRESS_TRACE_CHECK_LIMIT="$REEF_BENCHMARK_TRACE_LIMIT"
export DEV_STRESS_MIN_SUCCESS_RATE_PCT="$REEF_BENCHMARK_MIN_SUCCESS"
export DEV_STRESS_RATE_SCHEDULE=precise
export DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1

if [ "$REEF_BENCHMARK_IMAGE_MODE" = "dockerhub" ] && [ "$REEF_BENCHMARK_PROFILE" != "arena" ]; then
  export REEF_PLATFORM_RUNTIME_IMAGE="${REEF_PLATFORM_RUNTIME_IMAGE:-dills122/reef-platform-runtime:latest}"
  export REEF_MATCHING_ENGINE_IMAGE="${REEF_MATCHING_ENGINE_IMAGE:-dills122/reef-matching-engine:latest}"
  export DEV_COMPOSE_BUILD=0
  run_stage docker-compose-pull docker compose pull platform-api matching-engine platform-materializer platform-materializer-1 platform-materializer-2 platform-materializer-3
else
  export DEV_COMPOSE_BUILD="${DEV_COMPOSE_BUILD:-1}"
fi

if [ "$REEF_BENCHMARK_PROFILE" = "stream-ack" ]; then
  export DEV_STRESS_REPORT_OUT="$artifact_dir/stream-ack-stress.json"
  export DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS=1
  export DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR=1
  export DEV_STRESS_DB_SERVICES="${DEV_STRESS_DB_SERVICES:-postgres,projection-postgres}"

  run_stage make-dev-up-stream-ack make dev-up-stream-ack
  run_stage make-dev-smoke make dev-smoke
  run_stage make-dev-stress-stream-ack make dev-stress-stream-ack
elif [ "$REEF_BENCHMARK_PROFILE" = "materializer" ] || [ "$REEF_BENCHMARK_PROFILE" = "materializer-projection" ]; then
  export DEV_STRESS_REPORT_OUT="$artifact_dir/venue-event-materializer-stress.json"
  export MATCHING_ENGINE_EVENT_STREAM="$REEF_BENCHMARK_EVENT_STREAM"
  export MATCHING_ENGINE_EVENT_SUBJECT_PREFIX="$REEF_BENCHMARK_EVENT_SUBJECT_PREFIX"
  export VENUE_EVENT_MATERIALIZER_TOPIC="$REEF_BENCHMARK_EVENT_STREAM"
  export VENUE_EVENT_MATERIALIZER_GROUP_ID="$REEF_BENCHMARK_MATERIALIZER_GROUP"
  export ADMIN_API_TOKEN="${ADMIN_API_TOKEN:-local-admin}"
  export ARENA_ADMIN_API_TOKEN="${ARENA_ADMIN_API_TOKEN:-local-arena}"
  export REEF_ADMIN_API_BEARER_TOKEN="${REEF_ADMIN_API_BEARER_TOKEN:-$ADMIN_API_TOKEN}"
  if [ "$REEF_BENCHMARK_PROFILE" = "materializer-projection" ]; then
    export DEV_STRESS_DB_SERVICES="${DEV_STRESS_DB_SERVICES:-postgres,projection-postgres}"
  else
    export DEV_STRESS_DB_SERVICES="${DEV_STRESS_DB_SERVICES:-postgres}"
  fi

  run_stage make-dev-smoke-venue-event-materializer make dev-smoke-venue-event-materializer
  if [ "$REEF_BENCHMARK_PROFILE" = "materializer-projection" ]; then
    export STREAM_ACK_PROJECTOR_ENABLED=true
    export STREAM_ACK_PROJECTION_SOURCE=venue-event-batch
    export STREAM_ACK_PROJECTOR_INCLUDE_FILLS=true
    export STREAM_ACK_PROJECTION_NAME="${STREAM_ACK_PROJECTION_NAME:-runtime-normalized-venue-outcomes}"
    export STREAM_ACK_PROJECTOR_0_PARTITIONS="${STREAM_ACK_PROJECTOR_0_PARTITIONS:-0,1,2,3}"
    export STREAM_ACK_PROJECTOR_1_PARTITIONS="${STREAM_ACK_PROJECTOR_1_PARTITIONS:-4,5,6,7}"
    export STREAM_ACK_PROJECTOR_2_PARTITIONS="${STREAM_ACK_PROJECTOR_2_PARTITIONS:-8,9,10,11}"
    export STREAM_ACK_PROJECTOR_3_PARTITIONS="${STREAM_ACK_PROJECTOR_3_PARTITIONS:-12,13,14,15}"
    export STREAM_ACK_PROJECTION_STAGE="${STREAM_ACK_PROJECTION_STAGE:-${REEF_DO_PROJECTION_STAGE:-full}}"
    export ORDER_LIFECYCLE_PROJECTOR_ENABLED=true
    export MARKET_DATA_PROJECTOR_ENABLED=true
    export MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME="$STREAM_ACK_PROJECTION_NAME"
    export DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR=1
    export DEV_STRESS_FAIL_ON_STREAM_ACK_PROJECTOR_FAILURES=1
    export DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_FAILED_DELTA=0
    export DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_LAG="${DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_LAG:-0}"
    export DEV_STRESS_MAX_STREAM_ACK_PROJECTION_GAP="${DEV_STRESS_MAX_STREAM_ACK_PROJECTION_GAP:-0}"
    export DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_RETRY_DELTA="${DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_RETRY_DELTA:-${REEF_DO_MAX_PROJECTION_DB_RETRIES:-0}}"
    export DEV_STRESS_STREAM_ACK_PROJECTOR_DRAIN_WAIT_MS="${DEV_STRESS_STREAM_ACK_PROJECTOR_DRAIN_WAIT_MS:-60000}"
    export DEV_STRESS_STREAM_ACK_PROJECTOR_DRAIN_POLL_MS="${DEV_STRESS_STREAM_ACK_PROJECTOR_DRAIN_POLL_MS:-1000}"
  fi
  run_stage make-dev-stress-venue-event-materializer make dev-stress-venue-event-materializer
elif [ "$REEF_BENCHMARK_PROFILE" = "arena" ]; then
  arena_report="$artifact_dir/arena-local-tick-run.json"
  arena_summary="$artifact_dir/arena-local-tick-run.summary.json"
  arena_persisted_report="$artifact_dir/arena-local-tick-run.persisted.json"
  arena_export="$artifact_dir/arena-export.json"
  arena_command_timeout_ms="${REEF_BENCHMARK_ARENA_COMMAND_TIMEOUT_MS:-3000}"
  arena_command_poll_ms="${REEF_BENCHMARK_ARENA_COMMAND_POLL_MS:-100}"
  arena_projection_drain_timeout_ms="${REEF_BENCHMARK_ARENA_PROJECTION_DRAIN_TIMEOUT_MS:-30000}"
  arena_projection_drain_poll_ms="${REEF_BENCHMARK_ARENA_PROJECTION_DRAIN_POLL_MS:-100}"
  arena_projection_drain_cadence="${REEF_BENCHMARK_ARENA_PROJECTION_DRAIN_CADENCE:-scheduled-event}"
  arena_timeout_seconds="${REEF_BENCHMARK_ARENA_TIMEOUT_SECONDS:-$((REEF_BENCHMARK_ARENA_DURATION_SECONDS + 900))}"
  export ORDER_LIFECYCLE_PROJECTOR_ENABLED="${ORDER_LIFECYCLE_PROJECTOR_ENABLED:-true}"
  export MARKET_DATA_PROJECTOR_ENABLED="${MARKET_DATA_PROJECTOR_ENABLED:-true}"
  export ORDER_LIFECYCLE_PROJECTOR_POLL_MS="${ORDER_LIFECYCLE_PROJECTOR_POLL_MS:-100}"
  export MARKET_DATA_PROJECTOR_POLL_MS="${MARKET_DATA_PROJECTOR_POLL_MS:-100}"
  export ADMIN_API_TOKEN="${ADMIN_API_TOKEN:-local-admin}"
  export ARENA_ADMIN_API_TOKEN="${ARENA_ADMIN_API_TOKEN:-local-arena}"

  if ! command -v bun >/dev/null 2>&1; then
    run_stage install-bun curl -fsSL https://bun.sh/install -o /tmp/bun-install.sh
    run_stage install-bun-script bash /tmp/bun-install.sh
    export BUN_INSTALL="$HOME/.bun"
    export PATH="$BUN_INSTALL/bin:$PATH"
  fi

  export JS_RUNTIME=bun
  run_stage bun-install bun install --frozen-lockfile
  run_stage make-dev-up-stream-ack make dev-up-stream-ack
  run_stage make-dev-smoke make dev-smoke
  run_stage arena-local-tick-run timeout --preserve-status "${arena_timeout_seconds}s" bun scripts/dev/arena-local-tick-run.mjs \
    --run-id="$REEF_BENCHMARK_RUN_ID" \
    --compartment=ses \
    --submit-mode=live \
    --mode="$REEF_BENCHMARK_ARENA_MODE" \
    --venue-url=http://127.0.0.1:8080 \
    --arena-admin-url=http://127.0.0.1:8080 \
    --seed-reference \
    --duration-seconds="$REEF_BENCHMARK_ARENA_DURATION_SECONDS" \
    --tick-interval-ms="$REEF_BENCHMARK_ARENA_TICK_INTERVAL_MS" \
    --warmup-seconds="$REEF_BENCHMARK_ARENA_WARMUP_SECONDS" \
    --health-sample-interval-ms="$REEF_BENCHMARK_ARENA_HEALTH_SAMPLE_INTERVAL_MS" \
    --command-timeout-ms="$arena_command_timeout_ms" \
    --command-poll-ms="$arena_command_poll_ms" \
    --command-wait-mode="${REEF_BENCHMARK_ARENA_COMMAND_WAIT_MODE:-accepted}" \
    ${REEF_BENCHMARK_ARENA_SCORING_POLICY_VERSION:+--scoring-policy-version=$REEF_BENCHMARK_ARENA_SCORING_POLICY_VERSION} \
    --pace-ticks \
    --report-shape=compact \
    --projection-drain-timeout-ms="$arena_projection_drain_timeout_ms" \
    --projection-drain-poll-ms="$arena_projection_drain_poll_ms" \
    --projection-drain-cadence="$arena_projection_drain_cadence" \
    --require-projection-drain \
    --out="$arena_report"
  run_stage arena-hardening-summary bun scripts/dev/arena-local-hardening-run.mjs \
    --input-report="$arena_report" \
    --summary-out="$arena_summary"
  run_stage arena-persist-report-local bun scripts/dev/arena-persist-report-local.mjs \
    --report="$arena_report" \
    --out="$arena_persisted_report"
  run_stage arena-export bun scripts/dev/export-simulation-run.mjs \
    --report "$arena_persisted_report" \
    --artifact-root "$artifact_dir" \
    --run-kind arena-do \
    --source digitalocean \
    --profile "arena-${REEF_BENCHMARK_DURATION}" \
    --out "$arena_export"
else
  echo "unsupported REEF_DO_BENCHMARK_PROFILE=$REEF_BENCHMARK_PROFILE" >&2
  exit 2
fi
echo "[$(date -Is)] remote benchmark complete"
REMOTE
}

remote_collect_artifacts() {
  local run_id="$1"
  remote_script REEF_BENCHMARK_RUN_ID="$run_id" <<'REMOTE'
set -euo pipefail
artifact_dir="$REMOTE_ARTIFACT_ROOT/$REEF_BENCHMARK_RUN_ID"
log_dir="$artifact_dir/logs"
mkdir -p "$log_dir"
cd "$REMOTE_DIR"
docker compose ps > "$log_dir/docker-compose-ps.txt" 2>&1 || true
docker stats --no-stream > "$log_dir/docker-stats.txt" 2>&1 || true
docker compose logs --no-color --tail=1000 > "$log_dir/docker-compose.log" 2>&1 || true
free -h > "$log_dir/free.txt" 2>&1 || true
df -h > "$log_dir/df.txt" 2>&1 || true
uptime > "$log_dir/uptime.txt" 2>&1 || true
REMOTE
}

# Compresses and uploads the run's artifacts to Cloudflare R2 directly from
# the ephemeral worker, before fetch/destroy - this is the export/cleanup
# step from D-046's simulation-platform infra split. Reuses the same R2
# bucket/credential convention as the backbone's backup-dbs.sh so debug data
# has one object-storage vendor, not a second one. No-op (with a warning) if
# R2_ENDPOINT/R2_BUCKET/AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY are unset,
# since export is opt-in (REEF_DO_EXPORT_TO_R2=1) and credentials may not be
# configured for every local run.
remote_export_to_r2() {
  local run_id="$1"
  if [[ -z "${R2_ENDPOINT:-}" || -z "${R2_BUCKET:-}" || -z "${AWS_ACCESS_KEY_ID:-}" || -z "${AWS_SECRET_ACCESS_KEY:-}" ]]; then
    echo "R2_ENDPOINT/R2_BUCKET/AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY not fully set; skipping R2 export" >&2
    return 0
  fi
  remote_script \
    REEF_BENCHMARK_RUN_ID="$run_id" \
    R2_ENDPOINT="$R2_ENDPOINT" \
    R2_BUCKET="$R2_BUCKET" \
    AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    <<'REMOTE'
set -euo pipefail
artifact_dir="$REMOTE_ARTIFACT_ROOT/$REEF_BENCHMARK_RUN_ID"
archive="/tmp/${REEF_BENCHMARK_RUN_ID}.tar.gz"

if [ ! -d "$artifact_dir" ]; then
  echo "no artifact directory at $artifact_dir; skipping R2 export" >&2
  exit 0
fi

echo "compressing $artifact_dir -> $archive"
tar czf "$archive" -C "$REMOTE_ARTIFACT_ROOT" "$REEF_BENCHMARK_RUN_ID"

echo "uploading to R2..."
if command -v aws >/dev/null 2>&1; then
  AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
  AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
  AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
  aws --endpoint-url "$R2_ENDPOINT" \
    s3 cp "$archive" "s3://$R2_BUCKET/simulation-runs/${REEF_BENCHMARK_RUN_ID}.tar.gz"
else
  docker run --rm \
    -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
    -v "$(dirname "$archive"):/backups:ro" \
    amazon/aws-cli:2 \
    --endpoint-url "$R2_ENDPOINT" \
    s3 cp "/backups/$(basename "$archive")" "s3://$R2_BUCKET/simulation-runs/${REEF_BENCHMARK_RUN_ID}.tar.gz"
fi

rm -f "$archive"
echo "R2 export complete: simulation-runs/${REEF_BENCHMARK_RUN_ID}.tar.gz"
REMOTE
}

sync_repo() {
  local host user private_key
  host="$(droplet_ip)"
  user="$(ssh_user)"
  private_key="$(private_key_path)"
  echo "syncing repo to $user@$host:$REMOTE_DIR"
  rsync -az --delete \
    -e "ssh -i $private_key -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$ROOT_DIR/.tmp-do-known-hosts" \
    --exclude ".env" \
    --exclude ".env.local" \
    --exclude ".gradle/" \
    --exclude "build/" \
    --exclude "out/" \
    --exclude "bin/" \
    --exclude "coverage/" \
    --exclude "reports/do-benchmark/" \
    --exclude "infra/do-benchmark/.terraform/" \
    --exclude "infra/do-benchmark/terraform.tfstate" \
    --exclude "infra/do-benchmark/terraform.tfstate.backup" \
    "$ROOT_DIR/" "$user@$host:$REMOTE_DIR/"
}

fetch_artifacts() {
  local host user private_key
  host="$(droplet_ip)"
  user="$(ssh_user)"
  private_key="$(private_key_path)"
  mkdir -p "$LOCAL_REPORT_ROOT"
  echo "fetching artifacts into $LOCAL_REPORT_ROOT"
  rsync -az \
    -e "ssh -i $private_key -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$ROOT_DIR/.tmp-do-known-hosts" \
    "$user@$host:$REMOTE_ARTIFACT_ROOT/" "$LOCAL_REPORT_ROOT/"
}

wait_for_ssh() {
  local host user private_key
  host="$(droplet_ip)"
  user="$(ssh_user)"
  private_key="$(private_key_path)"
  echo "waiting for ssh on $user@$host"
  for _ in $(seq 1 90); do
    if ssh_base "$user" "$host" "$private_key" true >/dev/null 2>&1; then
      echo "ssh ready; waiting for cloud-init bootstrap"
      wait_for_bootstrap "$user" "$host" "$private_key"
      return
    fi
    sleep 5
  done
  echo "timed out waiting for ssh on $user@$host" >&2
  return 1
}

wait_for_bootstrap() {
  local user="$1"
  local host="$2"
  local private_key="$3"
  for _ in $(seq 1 120); do
    if ssh_base "$user" "$host" "$private_key" sudo test -f /var/log/reef-benchmark-bootstrap.done >/dev/null 2>&1; then
      echo "benchmark host bootstrap ready"
      return 0
    fi
    sleep 5
  done
  echo "timed out waiting for cloud-init benchmark bootstrap" >&2
  return 1
}

remote_script() {
  local env_args=()
  while [[ "$#" -gt 0 && "$1" == *=* ]]; do
    env_args+=("$1")
    shift
  done

  local host user private_key
  host="$(droplet_ip)"
  user="$(ssh_user)"
  private_key="$(private_key_path)"

  if [ "${#env_args[@]}" -gt 0 ]; then
    ssh_base "$user" "$host" "$private_key" \
      REMOTE_DIR="$REMOTE_DIR" \
      REMOTE_ARTIFACT_ROOT="$REMOTE_ARTIFACT_ROOT" \
      "${env_args[@]}" \
      bash -s
  else
    ssh_base "$user" "$host" "$private_key" \
      REMOTE_DIR="$REMOTE_DIR" \
      REMOTE_ARTIFACT_ROOT="$REMOTE_ARTIFACT_ROOT" \
      bash -s
  fi
}

ssh_base() {
  local user="$1"
  local host="$2"
  local private_key="$3"
  shift 3
  ssh \
    -i "$private_key" \
    -o StrictHostKeyChecking=accept-new \
    -o UserKnownHostsFile="$ROOT_DIR/.tmp-do-known-hosts" \
    -o ConnectTimeout=5 \
    "$user@$host" "$@"
}

configure_tf_vars() {
  local mode="${1:-required}"
  log "resolving DigitalOcean token"
  export TF_VAR_do_token="${DIGITALOCEAN_TOKEN:-${DO_TOKEN:-}}"
  if [ -z "$TF_VAR_do_token" ] && [ "$mode" != "optional" ]; then
    echo "DIGITALOCEAN_TOKEN or DO_TOKEN is required" >&2
    exit 1
  fi

  local public_key
  public_key="$(public_key_path)"
  log "using SSH public key path: $public_key"
  if [ ! -f "$public_key" ] && [ "$mode" != "optional" ]; then
    echo "SSH public key not found: $public_key" >&2
    exit 1
  fi
  if [ -f "$public_key" ]; then
    export TF_VAR_ssh_public_key
    TF_VAR_ssh_public_key="$(cat "$public_key")"
  elif [ "$mode" = "optional" ]; then
    export TF_VAR_ssh_public_key="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDummyKeyForOutputOnly000000000000000000000 reef-dummy"
  fi

  export TF_VAR_region="${REEF_DO_REGION:-sfo2}"
  export TF_VAR_size="${REEF_DO_SIZE:-$(benchmark_default_size "$(benchmark_profile)")}"
  export TF_VAR_image="${REEF_DO_IMAGE:-ubuntu-24-04-x64}"
  export TF_VAR_ssh_user="${REEF_DO_SSH_USER:-reefbench}"
  export TF_VAR_droplet_name="${REEF_DO_DROPLET_NAME:-reef-stream-ack-benchmark}"
  export TF_VAR_allowed_ssh_cidrs
  log "resolving allowed SSH CIDRs"
  TF_VAR_allowed_ssh_cidrs="$(allowed_ssh_cidrs_json "$mode")"
  log "allowed SSH CIDRs: $TF_VAR_allowed_ssh_cidrs"
}

allowed_ssh_cidrs_json() {
  local mode="$1"
  local raw="${REEF_DO_ALLOWED_SSH_CIDRS:-}"
  if [ -z "$raw" ]; then
    local ip
    ip="$(curl --max-time 5 -fsS https://api.ipify.org 2>/dev/null || true)"
    if [ -n "$ip" ]; then
      raw="$ip/32"
    fi
  fi
  if [ -z "$raw" ]; then
    if [ "$mode" = "optional" ]; then
      raw="127.0.0.1/32"
    else
      echo "Set REEF_DO_ALLOWED_SSH_CIDRS or allow auto-detection via https://api.ipify.org" >&2
      exit 1
    fi
  fi

  local json="["
  local first=1
  IFS=',' read -ra cidrs <<< "$raw"
  for cidr in "${cidrs[@]}"; do
    cidr="${cidr#"${cidr%%[![:space:]]*}"}"
    cidr="${cidr%"${cidr##*[![:space:]]}"}"
    [ -n "$cidr" ] || continue
    if [ "$first" -eq 0 ]; then
      json+=","
    fi
    json+="\"$cidr\""
    first=0
  done
  json+="]"
  printf '%s' "$json"
}

refresh_runtime_config() {
  REMOTE_DIR="${REEF_DO_REMOTE_DIR:-/home/${REEF_DO_SSH_USER:-reefbench}/reef}"
  REMOTE_ARTIFACT_ROOT="${REEF_DO_REMOTE_ARTIFACT_ROOT:-/tmp/reef-do-benchmark}"
}

require_destroyable_confirmation() {
  if [ "${REEF_DO_CONFIRM_DESTROYABLE:-}" != "1" ]; then
    echo "Set REEF_DO_CONFIRM_DESTROYABLE=1 to acknowledge this creates billable DigitalOcean resources." >&2
    exit 1
  fi
}

log() {
  printf '[do-benchmark] %s\n' "$*"
}

tofu() {
  local bin
  bin="$(tofu_bin)"
  (cd "$INFRA_DIR" && "$bin" "$@")
}

tofu_bin() {
  if [ -n "${TOFU_BIN:-}" ]; then
    printf '%s' "$TOFU_BIN"
    return
  fi
  if type -P tofu >/dev/null 2>&1; then
    type -P tofu
    return
  fi
  if type -P opentofu >/dev/null 2>&1; then
    type -P opentofu
    return
  fi
  if type -P terraform >/dev/null 2>&1; then
    type -P terraform
    return
  fi
  echo "missing tofu, opentofu, or terraform" >&2
  exit 1
}

droplet_ip() {
  tofu output -raw public_ipv4
}

ssh_user() {
  tofu output -raw ssh_user
}

public_key_path() {
  expand_path "${REEF_DO_SSH_PUBLIC_KEY:-$HOME/.ssh/id_ed25519.pub}"
}

private_key_path() {
  if [ -n "${REEF_DO_SSH_PRIVATE_KEY:-}" ]; then
    expand_path "$REEF_DO_SSH_PRIVATE_KEY"
    return
  fi
  local public_key
  public_key="$(public_key_path)"
  printf '%s' "${public_key%.pub}"
}

expand_path() {
  local path="$1"
  case "$path" in
    "~") printf '%s' "$HOME" ;;
    "~/"*) printf '%s/%s' "$HOME" "${path#~/}" ;;
    *) printf '%s' "$path" ;;
  esac
}

benchmark_profile() {
  local profile="${REEF_DO_BENCHMARK_PROFILE:-stream-ack}"
  case "$profile" in
    stream-ack|materializer|materializer-projection|arena) printf '%s' "$profile" ;;
    *)
      echo "unsupported REEF_DO_BENCHMARK_PROFILE=$profile" >&2
      exit 2
      ;;
  esac
}

benchmark_default_rates() {
  local profile="$1"
  local goal target
  goal="$(benchmark_goal)"
  target="$(benchmark_target_rps "$profile")"
  case "$goal" in
    latency-knee)
      if [ -n "$target" ]; then
        printf '%s,%s,%s,%s' \
          "$(round_rate $((target * 70 / 100)))" \
          "$(round_rate $((target * 90 / 100)))" \
          "$(round_rate $((target * 110 / 100)))" \
          "$(round_rate $((target * 130 / 100)))"
      else
        benchmark_fixed_default_rates "$profile"
      fi
      ;;
    sustain)
      if [ -n "$target" ]; then
        printf '%s' "$(round_rate "$target")"
      else
        benchmark_fixed_default_rates "$profile"
      fi
      ;;
    ceiling)
      if [ -n "$target" ]; then
        printf '%s,%s,%s,%s' \
          "$(round_rate $((target * 50 / 100)))" \
          "$(round_rate $((target * 75 / 100)))" \
          "$(round_rate "$target")" \
          "$(round_rate $((target * 125 / 100)))"
      else
        benchmark_fixed_default_rates "$profile"
      fi
      ;;
    fixed) benchmark_fixed_default_rates "$profile" ;;
    *)
      echo "unsupported REEF_DO_BENCHMARK_GOAL=$goal" >&2
      exit 2
      ;;
  esac
}

benchmark_fixed_default_rates() {
  case "$1" in
    arena) printf '%s' "arena" ;;
    materializer) printf '%s' "10000" ;;
    materializer-projection) printf '%s' "2500" ;;
    *) printf '%s' "2500,5000" ;;
  esac
}

benchmark_default_workers() {
  local profile="$1"
  local goal target
  goal="$(benchmark_goal)"
  target="$(benchmark_target_rps "$profile")"
  if [ "$goal" = "fixed" ] || [ -z "$target" ]; then
    case "$profile" in
      arena) printf '%s' "1" ;;
      materializer) printf '%s' "384" ;;
      materializer-projection) printf '%s' "256" ;;
      *) printf '%s' "256" ;;
    esac
    return
  fi
  if [ "$target" -le 5000 ]; then
    printf '%s' "512"
  elif [ "$target" -le 10000 ]; then
    printf '%s' "1024"
  else
    printf '%s' "1536"
  fi
}

benchmark_default_duration() {
  case "$1" in
    arena) printf '%s' "3m" ;;
    materializer) printf '%s' "60s" ;;
    materializer-projection) printf '%s' "60s" ;;
    *) printf '%s' "30s" ;;
  esac
}

benchmark_default_trace_limit() {
  case "$1" in
    arena) printf '%s' "0" ;;
    materializer) printf '%s' "0" ;;
    materializer-projection) printf '%s' "0" ;;
    *) printf '%s' "200" ;;
  esac
}

benchmark_goal() {
  local goal="${REEF_DO_BENCHMARK_GOAL:-${REEF_DO_GOAL:-}}"
  if [ -z "$goal" ]; then
    if [ -n "${REEF_DO_TARGET_ACCEPTED_RPS:-}" ]; then
      goal="sustain"
    else
      goal="fixed"
    fi
  fi
  case "$goal" in
    fixed|latency-knee|sustain|ceiling) printf '%s' "$goal" ;;
    *)
      echo "unsupported REEF_DO_BENCHMARK_GOAL=$goal" >&2
      exit 2
      ;;
  esac
}

benchmark_target_rps() {
  local profile="$1"
  local target="${REEF_DO_TARGET_ACCEPTED_RPS:-}"
  if [ -z "$target" ]; then
    case "$(benchmark_goal)" in
      latency-knee)
        case "$profile" in
          materializer) target="5000" ;;
          materializer-projection) target="2500" ;;
          *) target="2500" ;;
        esac
        ;;
      ceiling)
        case "$profile" in
          materializer) target="10000" ;;
          materializer-projection) target="2500" ;;
          *) target="5000" ;;
        esac
        ;;
      sustain)
        case "$profile" in
          materializer) target="10000" ;;
          materializer-projection) target="2500" ;;
          *) target="5000" ;;
        esac
        ;;
      *) target="" ;;
    esac
  fi
  if [ -n "$target" ]; then
    case "$target" in
      ''|*[!0-9]*)
        echo "REEF_DO_TARGET_ACCEPTED_RPS must be a positive integer, got $target" >&2
        exit 2
        ;;
    esac
    if [ "$target" -le 0 ]; then
      echo "REEF_DO_TARGET_ACCEPTED_RPS must be > 0" >&2
      exit 2
    fi
  fi
  printf '%s' "$target"
}

benchmark_default_size() {
  local profile="$1"
  local goal target
  goal="$(benchmark_goal)"
  target="$(benchmark_target_rps "$profile")"
  if [ "$goal" = "fixed" ] || [ -z "$target" ]; then
    printf '%s' "c-8"
    return
  fi
  if [ "$target" -le 5000 ]; then
    printf '%s' "c-8"
  elif [ "$target" -le 10000 ]; then
    printf '%s' "c-16"
  else
    printf '%s' "c-32"
  fi
}

benchmark_default_min_rps() {
  local profile="$1"
  local goal rates min_rate
  if [ "$profile" = "arena" ]; then
    printf ''
    return
  fi
  goal="$(benchmark_goal)"
  if [ "$goal" != "fixed" ]; then
    rates="${REEF_DO_STRESS_RATES:-$(benchmark_default_rates "$profile")}"
    min_rate="$(min_csv_rate "$rates")"
    if [ -n "$min_rate" ]; then
      printf '%s' "$((min_rate * 90 / 100))"
      return
    fi
  fi
  printf '%s' "2000"
}

min_csv_rate() {
  local raw="$1"
  local min=""
  local value
  IFS=',' read -ra values <<< "$raw"
  for value in "${values[@]}"; do
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    case "$value" in
      ''|*[!0-9]*) continue ;;
    esac
    if [ -z "$min" ] || [ "$value" -lt "$min" ]; then
      min="$value"
    fi
  done
  if [ -n "$min" ]; then
    printf '%s' "$min"
  else
    printf ''
  fi
}

round_rate() {
  local value="$1"
  printf '%s' "$(( ((value + 50) / 100) * 100 ))"
}

duration_to_seconds() {
  local raw="$1"
  case "$raw" in
    *ms)
      local value="${raw%ms}"
      printf '%s' "$(( (value + 999) / 1000 ))"
      ;;
    *s)
      printf '%s' "${raw%s}"
      ;;
    *m)
      local value="${raw%m}"
      printf '%s' "$(( value * 60 ))"
      ;;
    *h)
      local value="${raw%h}"
      printf '%s' "$(( value * 3600 ))"
      ;;
    ''|*[!0-9]*)
      echo "unsupported duration: $raw" >&2
      exit 2
      ;;
    *)
      printf '%s' "$raw"
      ;;
  esac
}

benchmark_run_id() {
  if [ -n "${REEF_DO_RUN_ID:-}" ]; then
    printf '%s' "$REEF_DO_RUN_ID"
    return
  fi
  date -u +"do-benchmark-%Y%m%dT%H%M%SZ"
}

benchmark_report_dir() {
  if [ -n "${REEF_DO_RUN_ID:-}" ]; then
    printf '%s/%s' "$LOCAL_REPORT_ROOT" "$REEF_DO_RUN_ID"
    return
  fi
  find "$LOCAL_REPORT_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'do-benchmark-*' -print |
    sort |
    tail -n 1
}

sanitize_token() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9_' '_'
}

main "$@"
