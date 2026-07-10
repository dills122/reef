#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
SECRETS="$BASE/secrets"

mkdir -p "$SECRETS"
chmod 700 "$SECRETS"

rand_hex() {
  openssl rand -hex 32
}

existing_env_value() {
  local file="$1"
  local key="$2"

  [[ -f "$file" ]] || return 0
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$file"
}

append_env_if_set() {
  local file="$1"
  local key="$2"
  local value="$3"

  [[ -n "$value" ]] || return 0
  printf '%s=%s\n' "$key" "$value" >> "$file"
}

static_client_tokens() {
  local prefix="$1"
  local count="$2"
  local token="$3"
  local out=""

  for ((i = 0; i < count; i++)); do
    if [[ -n "$out" ]]; then
      out+=","
    fi
    out+="${prefix}-${i}:${token}"
  done
  printf '%s\n' "$out"
}

if [[ ! -s "$SECRETS/db.env" ]]; then
  POSTGRES_PASSWORD="$(rand_hex)"
  OPENBAO_DB_PASSWORD="$(rand_hex)"
  REEF_DB_PASSWORD="$(rand_hex)"

  cat > "$SECRETS/db.env" <<EOF
POSTGRES_USER=postgres
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
OPENBAO_DB_PASSWORD=${OPENBAO_DB_PASSWORD}
REEF_DB_PASSWORD=${REEF_DB_PASSWORD}
EOF

  chmod 600 "$SECRETS/db.env"
fi

# shellcheck disable=SC1091
source "$SECRETS/db.env"

# Admin/analytics live in their own dedicated Postgres containers, not
# schemas in the main reef DB - see D-046 in docs/DECISIONS.md.
if [[ ! -s "$SECRETS/postgres-admin.env" ]]; then
  ADMIN_POSTGRES_PASSWORD="$(rand_hex)"
  ADMIN_APP_DB_PASSWORD="$(rand_hex)"

  cat > "$SECRETS/postgres-admin.env" <<EOF
POSTGRES_USER=postgres
POSTGRES_PASSWORD=${ADMIN_POSTGRES_PASSWORD}
POSTGRES_DB=admin
ADMIN_APP_DB_PASSWORD=${ADMIN_APP_DB_PASSWORD}
EOF

  chmod 600 "$SECRETS/postgres-admin.env"
fi

# shellcheck disable=SC1091
source "$SECRETS/postgres-admin.env"

if [[ ! -s "$SECRETS/postgres-analytics.env" ]]; then
  ANALYTICS_POSTGRES_PASSWORD="$(rand_hex)"
  ANALYTICS_APP_DB_PASSWORD="$(rand_hex)"

  cat > "$SECRETS/postgres-analytics.env" <<EOF
POSTGRES_USER=postgres
POSTGRES_PASSWORD=${ANALYTICS_POSTGRES_PASSWORD}
POSTGRES_DB=analytics
ANALYTICS_APP_DB_PASSWORD=${ANALYTICS_APP_DB_PASSWORD}
EOF

  chmod 600 "$SECRETS/postgres-analytics.env"
fi

# shellcheck disable=SC1091
source "$SECRETS/postgres-analytics.env"

# Bearer token gating narrow CI/run-plane admin gateway routes.
if [[ ! -s "$SECRETS/caddy.env" ]]; then
  ARENA_ADMIN_API_TOKEN="$(rand_hex)"
  ANALYTICS_EXPORT_API_TOKEN="$(rand_hex)"

  cat > "$SECRETS/caddy.env" <<EOF
ARENA_ADMIN_API_TOKEN=${ARENA_ADMIN_API_TOKEN}
ANALYTICS_EXPORT_API_TOKEN=${ANALYTICS_EXPORT_API_TOKEN}
EOF

  chmod 600 "$SECRETS/caddy.env"
  echo "Generated ARENA_ADMIN_API_TOKEN - set this as the ARENA_ADMIN_API_TOKEN GitHub Actions secret for the bot-submission workflow."
  echo "Generated ANALYTICS_EXPORT_API_TOKEN - use this for run-plane export posts to /admin/v1/analytics/run-exports."
fi

if ! grep -q '^ANALYTICS_EXPORT_API_TOKEN=' "$SECRETS/caddy.env"; then
  ANALYTICS_EXPORT_API_TOKEN="$(rand_hex)"
  cat >> "$SECRETS/caddy.env" <<EOF
ANALYTICS_EXPORT_API_TOKEN=${ANALYTICS_EXPORT_API_TOKEN}
EOF
  echo "Generated missing ANALYTICS_EXPORT_API_TOKEN - use this for run-plane export posts to /admin/v1/analytics/run-exports."
fi

# shellcheck disable=SC1091
source "$SECRETS/caddy.env"

if [[ -f "$SECRETS/admin-auth.env" ]]; then
  # shellcheck disable=SC1091
  source "$SECRETS/admin-auth.env"
fi

PLATFORM_BAO_ROLE_ID="$(existing_env_value "$SECRETS/platform-runtime.env" BAO_ROLE_ID)"
PLATFORM_BAO_SECRET_ID="$(existing_env_value "$SECRETS/platform-runtime.env" BAO_SECRET_ID)"
SIMULATOR_BAO_ROLE_ID="$(existing_env_value "$SECRETS/simulator.env" BAO_ROLE_ID)"
SIMULATOR_BAO_SECRET_ID="$(existing_env_value "$SECRETS/simulator.env" BAO_SECRET_ID)"
SIMULATOR_API_TOKEN="$(existing_env_value "$SECRETS/simulator.env" REEF_API_BEARER_TOKEN)"
if [[ -z "$SIMULATOR_API_TOKEN" ]]; then
  SIMULATOR_API_TOKEN="$(rand_hex)"
fi
SIMULATOR_CLIENT_ID_PREFIX="${SIMULATOR_CLIENT_ID_PREFIX:-sim-client}"
SIMULATOR_STATIC_CLIENT_COUNT="${SIMULATOR_STATIC_CLIENT_COUNT:-512}"
SIMULATOR_API_TOKENS="$(static_client_tokens "$SIMULATOR_CLIENT_ID_PREFIX" "$SIMULATOR_STATIC_CLIENT_COUNT" "$SIMULATOR_API_TOKEN")"

cat > "$SECRETS/openbao.env" <<EOF
BAO_ADDR=http://127.0.0.1:8200
BAO_PG_CONNECTION_URL=postgres://openbao:${OPENBAO_DB_PASSWORD}@postgres:5432/openbao?sslmode=disable
EOF
chmod 600 "$SECRETS/openbao.env"

cat > "$SECRETS/platform-runtime.env" <<EOF
PLATFORM_RUNTIME_PORT=8080
EXTERNAL_API_DEPLOYMENT_PROFILE=production
ENGINE_DEPLOYMENT_PROFILE=hosted-single-host
PLATFORM_ARENA_ADMIN_ENABLED=1
PLATFORM_ADMIN_AUTH_ENABLED=${PLATFORM_ADMIN_AUTH_ENABLED:-false}
PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=${PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED:-false}
ADMIN_SESSION_COOKIE_NAME=${ADMIN_SESSION_COOKIE_NAME:-reef_admin_session}
ADMIN_SESSION_COOKIE_SECURE=${ADMIN_SESSION_COOKIE_SECURE:-true}
PLATFORM_INTERNAL_HTTP_MODE=disabled
EXTERNAL_API_AUTH_MODE=static-token
EXTERNAL_API_TOKENS=${SIMULATOR_API_TOKENS}
EXTERNAL_API_RATE_LIMIT_MODE=fixed-window
EXTERNAL_API_IDEMPOTENCY_STORE=postgres
ENGINE_TRANSPORT=grpc
ENGINE_GRPC_SECURITY=plaintext
MATCHING_ENGINE_BASE_URL=http://matching-engine:8081
MATCHING_ENGINE_GRPC_TARGET=matching-engine:9081
RUNTIME_PERSISTENCE=postgres
RUNTIME_DB_BOOTSTRAP_MODE=validate
RUNTIME_POSTGRES_JDBC_URL=jdbc:postgresql://postgres:5432/reef?currentSchema=public
RUNTIME_POSTGRES_USER=reef_app
RUNTIME_POSTGRES_PASSWORD=${REEF_DB_PASSWORD}
RUNTIME_DB_URL=jdbc:postgresql://postgres:5432/reef?currentSchema=public
RUNTIME_DB_USER=reef_app
RUNTIME_DB_PASSWORD=${REEF_DB_PASSWORD}
EXTERNAL_API_COMMAND_CAPTURE_MODE=postgres
EXTERNAL_API_COMMAND_LOG_MODE=disabled
EXTERNAL_API_COMMAND_PROCESSING_MODE=sync-result
EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=false
STREAM_ACK_WORKER_ENABLED=false
STREAM_ACK_PROJECTOR_ENABLED=false
BAO_ADDR=http://openbao:8200
ADMIN_POSTGRES_JDBC_URL=jdbc:postgresql://postgres-admin:5432/admin?currentSchema=admin
ADMIN_POSTGRES_USER=admin_app
ADMIN_POSTGRES_PASSWORD=${ADMIN_APP_DB_PASSWORD}
GITHUB_OAUTH_CLIENT_ID=${GITHUB_OAUTH_CLIENT_ID:-}
GITHUB_OAUTH_CLIENT_SECRET=${GITHUB_OAUTH_CLIENT_SECRET:-}
GITHUB_OAUTH_REDIRECT_URI=${GITHUB_OAUTH_REDIRECT_URI:-}
ARENA_POSTGRES_JDBC_URL=jdbc:postgresql://postgres-admin:5432/admin?currentSchema=arena
ARENA_POSTGRES_USER=admin_app
ARENA_POSTGRES_PASSWORD=${ADMIN_APP_DB_PASSWORD}
ANALYTICS_POSTGRES_JDBC_URL=jdbc:postgresql://postgres-analytics:5432/analytics?currentSchema=analytics
ANALYTICS_POSTGRES_USER=analytics_app
ANALYTICS_POSTGRES_PASSWORD=${ANALYTICS_APP_DB_PASSWORD}
ARENA_ADMIN_API_TOKEN=${ARENA_ADMIN_API_TOKEN}
ANALYTICS_EXPORT_API_TOKEN=${ANALYTICS_EXPORT_API_TOKEN}
EOF
append_env_if_set "$SECRETS/platform-runtime.env" BAO_ROLE_ID "$PLATFORM_BAO_ROLE_ID"
append_env_if_set "$SECRETS/platform-runtime.env" BAO_SECRET_ID "$PLATFORM_BAO_SECRET_ID"
chmod 600 "$SECRETS/platform-runtime.env"

cat > "$SECRETS/matching-engine.env" <<EOF
MATCHING_ENGINE_ADDR=:8081
MATCHING_ENGINE_GRPC_ADDR=:9081
MATCHING_ENGINE_ENABLE_GRPC=1
EOF
chmod 600 "$SECRETS/matching-engine.env"

cat > "$SECRETS/simulator.env" <<EOF
LOAD_TESTER_BASE_URL=http://platform-runtime:8080
BAO_ADDR=http://openbao:8200
REEF_CLIENT_ID_PREFIX=${SIMULATOR_CLIENT_ID_PREFIX}
REEF_API_BEARER_TOKEN=${SIMULATOR_API_TOKEN}
EOF
append_env_if_set "$SECRETS/simulator.env" BAO_ROLE_ID "$SIMULATOR_BAO_ROLE_ID"
append_env_if_set "$SECRETS/simulator.env" BAO_SECRET_ID "$SIMULATOR_BAO_SECRET_ID"
chmod 600 "$SECRETS/simulator.env"

echo "Local service secrets generated under $SECRETS."
echo "Append OpenBao AppRole credentials after OpenBao is initialized."
