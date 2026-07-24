#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

DEPLOY_ROOT="${REEF_DEPLOY_DIR:-/opt/reef}"
MAX_MIGRATION_ARCHIVE_BYTES="${REEF_DEPLOY_MAX_MIGRATION_ARCHIVE_BYTES:-2097152}"
HEALTH_ATTEMPTS="${REEF_DEPLOY_HEALTH_ATTEMPTS:-45}"
HEALTH_DELAY_SECONDS="${REEF_DEPLOY_HEALTH_DELAY_SECONDS:-2}"
DEPLOY_LOG="${REEF_DEPLOY_LOG:-$DEPLOY_ROOT/deployments/application-services.jsonl}"

temp_root=""
env_backup=""
restore_images_on_error=0

fail() {
  echo "application service deploy: $*" >&2
  return 1
}

require_uint() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value <= 0 )); then
    fail "$name must be a positive integer"
  fi
}

read_env_value() {
  local name="$1"
  local file="$2"
  [[ -f "$file" ]] || return 0
  sed -n "s/^${name}=//p" "$file" | tail -n 1
}

openbao_health() {
  local port="$1"
  local payload
  payload="$(curl -fsS "http://127.0.0.1:${port}/v1/sys/health")" ||
    fail "OpenBao is not active and unsealed"
  grep -Eq '"sealed"[[:space:]]*:[[:space:]]*false' <<<"$payload" ||
    fail "OpenBao health response did not confirm sealed=false"
}

wait_for_runtime_health() {
  local port="$1"
  local attempt
  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    if curl -fsS "http://127.0.0.1:${port}/health" >/dev/null; then
      return 0
    fi
    sleep "$HEALTH_DELAY_SECONDS"
  done
  fail "platform-runtime health did not recover after ${HEALTH_ATTEMPTS} attempts"
}

verify_image_revision() {
  local image="$1"
  local expected_sha="$2"
  local revision
  revision="$(
    docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
      "$image"
  )"
  revision="$(printf '%s' "$revision" | tr '[:upper:]' '[:lower:]')"
  [[ "$revision" == "$expected_sha" ]] ||
    fail "image revision label does not match requested commit: $image"
}

validate_migration_archive() {
  local archive="$1"
  local listing metadata entry permissions clean

  listing="$(tar -tzf "$archive")" || fail "migration archive is not a readable gzip tar"
  [[ -n "$listing" ]] || fail "migration archive is empty"

  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    clean="${entry#./}"
    [[ "$clean" != /* ]] ||
      fail "migration archive contains an unsafe path"
    [[ "$clean" == "migrations" || "$clean" == "migrations/" || "$clean" == migrations/* ]] ||
      fail "migration archive contains a path outside migrations/"
    [[ "/$clean/" != *"/../"* && "/$clean/" != *"/./"* ]] ||
      fail "migration archive contains a traversal path"
    if [[ "$clean" != */ && "$clean" != "migrations" ]]; then
      [[ "$clean" =~ ^migrations/[a-zA-Z_][a-zA-Z0-9_]*/[0-9]{4}_[a-zA-Z0-9_.-]+\.sql$ ]] ||
        fail "migration archive contains an unexpected file: $clean"
    fi
  done <<<"$listing"

  metadata="$(tar -tvzf "$archive")" || fail "migration archive metadata is unreadable"
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    permissions="${entry#"${entry%%[![:space:]]*}"}"
    permissions="${permissions%%[[:space:]]*}"
    [[ "${permissions:0:1}" == "-" || "${permissions:0:1}" == "d" ]] ||
      fail "migration archive contains a link or unsupported file type"
  done <<<"$metadata"
}

capture_stdin_archive() {
  local archive="$1"
  local actual_bytes
  head -c "$((MAX_MIGRATION_ARCHIVE_BYTES + 1))" >"$archive"
  actual_bytes="$(wc -c <"$archive" | tr -d '[:space:]')"
  (( actual_bytes > 0 )) || fail "migration archive was not supplied"
  (( actual_bytes <= MAX_MIGRATION_ARCHIVE_BYTES )) ||
    fail "migration archive exceeds ${MAX_MIGRATION_ARCHIVE_BYTES} bytes"
}

update_image_pins() {
  local env_file="$1"
  local runtime_image="$2"
  local matching_image="$3"
  local simulator_image="$4"
  local next_env

  next_env="$(mktemp "$DEPLOY_ROOT/.env.next.XXXXXX")"
  if [[ -f "$env_file" ]]; then
    grep -Ev '^REEF_(PLATFORM_RUNTIME|MATCHING_ENGINE|SIMULATOR)_IMAGE=' "$env_file" >"$next_env" || true
    chmod 600 "$next_env"
  else
    chmod 600 "$next_env"
  fi
  printf '%s\n' \
    "REEF_PLATFORM_RUNTIME_IMAGE=$runtime_image" \
    "REEF_MATCHING_ENGINE_IMAGE=$matching_image" \
    "REEF_SIMULATOR_IMAGE=$simulator_image" >>"$next_env"
  mv "$next_env" "$env_file"
}

apply_migrations() {
  local migrations_root="$1"

  REEF_DEPLOY_DIR="$DEPLOY_ROOT" \
    REEF_MIGRATIONS_ROOT="$migrations_root" \
    "$DEPLOY_ROOT/scripts/apply-migrations.sh"

  REEF_DEPLOY_DIR="$DEPLOY_ROOT" \
    REEF_MIGRATIONS_ROOT="$migrations_root" \
    REEF_MIGRATION_DOMAINS="admin arena" \
    REEF_APP_USER="admin_app" \
    REEF_POSTGRES_SERVICE="postgres-admin" \
    REEF_POSTGRES_DB="admin" \
    "$DEPLOY_ROOT/scripts/apply-migrations.sh"

  REEF_DEPLOY_DIR="$DEPLOY_ROOT" \
    REEF_MIGRATIONS_ROOT="$migrations_root" \
    REEF_MIGRATION_DOMAINS="analytics" \
    REEF_APP_USER="analytics_app" \
    REEF_POSTGRES_SERVICE="postgres-analytics" \
    REEF_POSTGRES_DB="analytics" \
    "$DEPLOY_ROOT/scripts/apply-migrations.sh"
}

rollback_on_error() {
  local status=$?
  trap - ERR
  if (( restore_images_on_error == 1 )) && [[ -n "$env_backup" && -f "$env_backup" ]]; then
    echo "application service deploy failed; restoring previous image pins" >&2
    cp -p "$env_backup" "$DEPLOY_ROOT/.env"
    (
      cd "$DEPLOY_ROOT"
      docker compose up -d --no-deps --force-recreate matching-engine
      docker compose up -d --no-deps --force-recreate platform-runtime
    ) || echo "automatic image rollback failed; operator intervention is required" >&2
  fi
  exit "$status"
}

cleanup() {
  if [[ -n "$temp_root" ]]; then
    rm -rf "$temp_root"
  fi
}

trap rollback_on_error ERR
trap cleanup EXIT

mode="${1:-}"
migration_archive="${REEF_MIGRATION_ARCHIVE:-}"

if [[ "$mode" == "--ssh-command" ]]; then
  read -r -a original_command <<<"${SSH_ORIGINAL_COMMAND:-}"
  [[ "${#original_command[@]}" -eq 2 && "${original_command[0]}" == "deploy" ]] ||
    fail "the SSH deploy key only accepts: deploy <40-character-git-sha>"
  git_sha="${original_command[1]}"
  temp_root="$(mktemp -d)"
  migration_archive="$temp_root/migrations.tar.gz"
  capture_stdin_archive "$migration_archive"
elif [[ "$mode" == "deploy" ]]; then
  git_sha="${2:-}"
  migration_archive="${3:-$migration_archive}"
else
  fail "usage: $0 deploy <40-character-git-sha> <migrations.tar.gz>"
fi

[[ "$git_sha" =~ ^[a-fA-F0-9]{40}$ ]] ||
  fail "git SHA must contain exactly 40 hexadecimal characters"
git_sha="$(printf '%s' "$git_sha" | tr '[:upper:]' '[:lower:]')"
short_sha="${git_sha:0:7}"

[[ "$DEPLOY_ROOT" =~ ^/[a-zA-Z0-9._/-]+$ ]] ||
  fail "REEF_DEPLOY_DIR must be an absolute path without shell metacharacters"
[[ -d "$DEPLOY_ROOT" ]] || fail "deployment root does not exist: $DEPLOY_ROOT"
[[ -x "$DEPLOY_ROOT/scripts/apply-migrations.sh" ]] ||
  fail "migration runner is missing or not executable"
[[ -n "$migration_archive" && -f "$migration_archive" ]] ||
  fail "migration archive does not exist"

require_uint "REEF_DEPLOY_MAX_MIGRATION_ARCHIVE_BYTES" "$MAX_MIGRATION_ARCHIVE_BYTES"
require_uint "REEF_DEPLOY_HEALTH_ATTEMPTS" "$HEALTH_ATTEMPTS"
require_uint "REEF_DEPLOY_HEALTH_DELAY_SECONDS" "$HEALTH_DELAY_SECONDS"

if [[ -z "$temp_root" ]]; then
  temp_root="$(mktemp -d)"
fi
validate_migration_archive "$migration_archive"
mkdir -p "$temp_root/extracted"
tar -xzf "$migration_archive" -C "$temp_root/extracted"
migrations_root="$temp_root/extracted/migrations"
[[ -d "$migrations_root" ]] || fail "migration archive is missing migrations/"

env_file="$DEPLOY_ROOT/.env"
namespace="${REEF_AUTO_DEPLOY_IMAGE_NAMESPACE:-$(read_env_value REEF_AUTO_DEPLOY_IMAGE_NAMESPACE "$env_file")}"
namespace="${namespace:-dills122}"
[[ "$namespace" =~ ^[a-z0-9][a-z0-9._:-]*(/[a-z0-9][a-z0-9._-]*)*$ ]] ||
  fail "invalid REEF_AUTO_DEPLOY_IMAGE_NAMESPACE"

runtime_image="${namespace}/reef-arena-platform-runtime:sha-${short_sha}"
matching_image="${namespace}/reef-matching-engine:sha-${short_sha}"
simulator_image="${namespace}/reef-simulator:sha-${short_sha}"

openbao_port="$(read_env_value REEF_BACKBONE_OPENBAO_HOST_PORT "$env_file")"
openbao_port="${openbao_port:-8200}"
platform_port="$(read_env_value REEF_BACKBONE_PLATFORM_HOST_PORT "$env_file")"
platform_port="${platform_port:-8080}"
[[ "$openbao_port" =~ ^[0-9]+$ && "$platform_port" =~ ^[0-9]+$ ]] ||
  fail "configured host ports must be numeric"

mkdir -p "$DEPLOY_ROOT/deployments"
exec 9>"$DEPLOY_ROOT/deployments/application-services.lock"
flock 9

cd "$DEPLOY_ROOT"
compose_services="$(docker compose --profile manual config --services)"
for service in openbao matching-engine platform-runtime simulator; do
  grep -Fxq "$service" <<<"$compose_services" ||
    fail "compose service is missing: $service"
done

openbao_container_before="$(docker compose ps -q openbao)"
[[ -n "$openbao_container_before" ]] || fail "OpenBao container is not running"
[[ "$(docker inspect -f '{{.State.Running}}' "$openbao_container_before")" == "true" ]] ||
  fail "OpenBao container is not running"
openbao_health "$openbao_port"

echo "pulling immutable application images for $git_sha"
docker pull "$matching_image"
docker pull "$runtime_image"
docker pull "$simulator_image"
verify_image_revision "$matching_image" "$git_sha"
verify_image_revision "$runtime_image" "$git_sha"
verify_image_revision "$simulator_image" "$git_sha"

apply_migrations "$migrations_root"

env_backup="$DEPLOY_ROOT/deployments/.env.before-${git_sha}-$(date -u +%Y%m%dT%H%M%SZ)"
if [[ -f "$env_file" ]]; then
  cp -p "$env_file" "$env_backup"
else
  : >"$env_backup"
  chmod 600 "$env_backup"
fi
update_image_pins "$env_file" "$runtime_image" "$matching_image" "$simulator_image"
restore_images_on_error=1

# These are deliberately per-service, dependency-free recreates. In particular,
# neither command is permitted to recreate OpenBao or any Postgres container.
docker compose up -d --no-deps --force-recreate matching-engine
docker compose up -d --no-deps --force-recreate platform-runtime

matching_container="$(docker compose ps -q matching-engine)"
runtime_container="$(docker compose ps -q platform-runtime)"
[[ -n "$matching_container" && -n "$runtime_container" ]] ||
  fail "an application container is missing after deployment"
[[ "$(docker inspect -f '{{.State.Running}}' "$matching_container")" == "true" ]] ||
  fail "matching-engine is not running after deployment"
[[ "$(docker inspect -f '{{.State.Running}}' "$runtime_container")" == "true" ]] ||
  fail "platform-runtime is not running after deployment"
wait_for_runtime_health "$platform_port"

openbao_container_after="$(docker compose ps -q openbao)"
[[ "$openbao_container_after" == "$openbao_container_before" ]] ||
  fail "OpenBao container identity changed during application deployment"
openbao_health "$openbao_port"

restore_images_on_error=0
deployed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '{"deployedAt":"%s","gitSha":"%s","platformRuntimeImage":"%s","matchingEngineImage":"%s","simulatorImage":"%s","simulatorStarted":false,"openBaoContainer":"%s"}\n' \
  "$deployed_at" \
  "$git_sha" \
  "$runtime_image" \
  "$matching_image" \
  "$simulator_image" \
  "$openbao_container_after" >>"$DEPLOY_LOG"

docker compose ps openbao matching-engine platform-runtime
echo "application service deploy complete for $git_sha"
echo "simulator image pinned but not started: $simulator_image"
