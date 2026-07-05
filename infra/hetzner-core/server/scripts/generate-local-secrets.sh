#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
SECRETS="$BASE/secrets"

mkdir -p "$SECRETS"
chmod 700 "$SECRETS"

rand_hex() {
  openssl rand -hex 32
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

cat > "$SECRETS/openbao.env" <<EOF
BAO_ADDR=http://127.0.0.1:8200
BAO_PG_CONNECTION_URL=postgres://openbao:${OPENBAO_DB_PASSWORD}@postgres:5432/openbao?sslmode=disable
EOF
chmod 600 "$SECRETS/openbao.env"

cat > "$SECRETS/platform-runtime.env" <<EOF
PLATFORM_RUNTIME_PORT=8080
ENGINE_TRANSPORT=http
MATCHING_ENGINE_BASE_URL=http://matching-engine:8081
MATCHING_ENGINE_GRPC_TARGET=matching-engine:9081
RUNTIME_PERSISTENCE=postgres
RUNTIME_DB_BOOTSTRAP_MODE=validate
RUNTIME_POSTGRES_JDBC_URL=jdbc:postgresql://postgres:5432/reef?currentSchema=public
RUNTIME_POSTGRES_USER=reef_app
RUNTIME_POSTGRES_PASSWORD=${REEF_DB_PASSWORD}
EXTERNAL_API_IDEMPOTENCY_STORE=postgres
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
EOF
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
EOF
chmod 600 "$SECRETS/simulator.env"

echo "Local service secrets generated under $SECRETS."
echo "Append OpenBao AppRole credentials after OpenBao is initialized."
