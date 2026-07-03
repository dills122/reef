#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INFRA_DIR="$ROOT_DIR/infra/do-benchmark"
LOCAL_REPORT_ROOT="$ROOT_DIR/reports/do-benchmark"
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
  status         print OpenTofu outputs
  sync           rsync the current checkout to the droplet
  start          start the remote stream-ack stack
  run            provision, sync, run the benchmark, fetch artifacts, and check reports
  remote-status  show remote host and compose status
  logs           fetch recent remote compose logs to stdout
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
  REEF_DO_REGION=sfo3
  REEF_DO_SIZE=c-8
  REEF_DO_STRESS_RATES=2500,5000
  REEF_DO_STRESS_WORKERS=256
  REEF_DO_STRESS_DURATION=30s
USAGE
}

main() {
  cd "$ROOT_DIR"
  load_env_file "$ROOT_DIR/.env"
  load_env_file "$ROOT_DIR/.env.local"
  refresh_runtime_config

  local command="${1:-}"
  case "$command" in
    up) cmd_up ;;
    status) cmd_status ;;
    sync) cmd_sync ;;
    start) cmd_start ;;
    run) cmd_run ;;
    remote-status) cmd_remote_status ;;
    logs) cmd_logs ;;
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
  fetch_artifacts || status=$?
  REEF_DO_REQUIRED_RATES="${REEF_DO_REQUIRED_RATES:-${REEF_DO_STRESS_RATES:-2500,5000}}" \
    node scripts/dev/do-benchmark-check.mjs "$LOCAL_REPORT_ROOT/$run_id" || status=$?
  return "$status"
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

cmd_logs() {
  configure_tf_vars optional
  wait_for_ssh
  remote_script <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
docker compose logs --no-color --tail="${REEF_DO_LOG_TAIL:-300}" || true
REMOTE
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
  configure_tf_vars
  tofu init
  tofu apply -auto-approve
  wait_for_ssh
}

remote_start_stack() {
  remote_script <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
export JS_RUNTIME=node
export STREAM_ACK_COMMAND_STREAM="${STREAM_ACK_COMMAND_STREAM:-REEF_COMMANDS}"
make dev-up-stream-ack
REMOTE
}

remote_run_benchmark() {
  local run_id="$1"
  local stream_name
  stream_name="REEF_COMMANDS_$(sanitize_token "$run_id")"
  local durable_prefix
  durable_prefix="reef-stream-worker-$(sanitize_token "$run_id")"
  local rates="${REEF_DO_STRESS_RATES:-2500,5000}"
  local workers="${REEF_DO_STRESS_WORKERS:-256}"
  local duration="${REEF_DO_STRESS_DURATION:-30s}"
  local trace_limit="${REEF_DO_TRACE_CHECK_LIMIT:-200}"
  local min_success="${REEF_DO_MIN_SUCCESS_RATE_PCT:-100}"

  echo "running remote benchmark run_id=$run_id stream=$stream_name rates=$rates workers=$workers duration=$duration"
  remote_script \
    REEF_BENCHMARK_RUN_ID="$run_id" \
    REEF_BENCHMARK_STREAM="$stream_name" \
    REEF_BENCHMARK_DURABLE_PREFIX="$durable_prefix" \
    REEF_BENCHMARK_RATES="$rates" \
    REEF_BENCHMARK_WORKERS="$workers" \
    REEF_BENCHMARK_DURATION="$duration" \
    REEF_BENCHMARK_TRACE_LIMIT="$trace_limit" \
    REEF_BENCHMARK_MIN_SUCCESS="$min_success" <<'REMOTE'
set -euo pipefail
artifact_dir="$REMOTE_ARTIFACT_ROOT/$REEF_BENCHMARK_RUN_ID"
mkdir -p "$artifact_dir"
cd "$REMOTE_DIR"

export JS_RUNTIME=node
export STREAM_ACK_COMMAND_STREAM="$REEF_BENCHMARK_STREAM"
export STREAM_ACK_WORKER_DURABLE_PREFIX="$REEF_BENCHMARK_DURABLE_PREFIX"
export DEV_STRESS_ARTIFACT_DIR="$artifact_dir"
export DEV_STRESS_REPORT_OUT="$artifact_dir/stream-ack-stress.json"
export DEV_STRESS_RATES="$REEF_BENCHMARK_RATES"
export DEV_STRESS_SWEEP_WORKERS="$REEF_BENCHMARK_WORKERS"
export DEV_STRESS_DURATION="$REEF_BENCHMARK_DURATION"
export DEV_STRESS_TRACE_CHECK_LIMIT="$REEF_BENCHMARK_TRACE_LIMIT"
export DEV_STRESS_MIN_SUCCESS_RATE_PCT="$REEF_BENCHMARK_MIN_SUCCESS"
export DEV_STRESS_RATE_SCHEDULE=precise
export DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS=1
export DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR=1
export DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1

make dev-up-stream-ack
make dev-smoke
make dev-stress-stream-ack
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

  ssh_base "$user" "$host" "$private_key" \
    REMOTE_DIR="$REMOTE_DIR" \
    REMOTE_ARTIFACT_ROOT="$REMOTE_ARTIFACT_ROOT" \
    "${env_args[@]}" \
    bash -s
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
  export TF_VAR_do_token="${DIGITALOCEAN_TOKEN:-${DO_TOKEN:-}}"
  if [ -z "$TF_VAR_do_token" ] && [ "$mode" != "optional" ]; then
    echo "DIGITALOCEAN_TOKEN or DO_TOKEN is required" >&2
    exit 1
  fi

  local public_key
  public_key="$(public_key_path)"
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

  export TF_VAR_region="${REEF_DO_REGION:-sfo3}"
  export TF_VAR_size="${REEF_DO_SIZE:-c-8}"
  export TF_VAR_image="${REEF_DO_IMAGE:-ubuntu-24-04-x64}"
  export TF_VAR_ssh_user="${REEF_DO_SSH_USER:-reefbench}"
  export TF_VAR_droplet_name="${REEF_DO_DROPLET_NAME:-reef-stream-ack-benchmark}"
  export TF_VAR_allowed_ssh_cidrs
  TF_VAR_allowed_ssh_cidrs="$(allowed_ssh_cidrs_json "$mode")"
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
  if command -v tofu >/dev/null 2>&1; then
    printf 'tofu'
    return
  fi
  if command -v opentofu >/dev/null 2>&1; then
    printf 'opentofu'
    return
  fi
  if command -v terraform >/dev/null 2>&1; then
    printf 'terraform'
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
  expand_path "${REEF_DO_SSH_PUBLIC_KEY:-~/.ssh/id_ed25519.pub}"
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

benchmark_run_id() {
  if [ -n "${REEF_DO_RUN_ID:-}" ]; then
    printf '%s' "$REEF_DO_RUN_ID"
    return
  fi
  date -u +"do-benchmark-%Y%m%dT%H%M%SZ"
}

sanitize_token() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9_' '_'
}

main "$@"
