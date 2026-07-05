#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

if [[ -z "${BAO_TOKEN:-}" ]]; then
  echo "BAO_TOKEN must be set to a valid OpenBao token for bootstrap." >&2
  exit 1
fi

bao() {
  docker compose exec -T \
    -e BAO_ADDR=http://127.0.0.1:8200 \
    -e BAO_TOKEN="$BAO_TOKEN" \
    openbao bao "$@"
}

has_json_key() {
  local key="$1"
  jq -e --arg key "$key" 'has($key)' >/dev/null
}

if ! bao audit list -format=json | has_json_key "file/"; then
  bao audit enable file file_path=/bao/logs/audit.log
else
  echo "skip audit file/"
fi

if ! bao secrets list -format=json | has_json_key "secret/"; then
  bao secrets enable -path=secret -version=2 kv
else
  echo "skip secrets secret/"
fi

if ! bao auth list -format=json | has_json_key "approle/"; then
  bao auth enable approle
else
  echo "skip auth approle/"
fi

cat <<'POLICY' | bao policy write reef-platform-runtime -
path "secret/data/bots/*" {
  capabilities = ["read"]
}

path "secret/metadata/bots/*" {
  capabilities = ["list", "read"]
}

path "secret/data/services/platform-runtime/*" {
  capabilities = ["read"]
}
POLICY

cat <<'POLICY' | bao policy write reef-simulator -
path "secret/data/bots/*" {
  capabilities = ["read"]
}

path "secret/metadata/bots/*" {
  capabilities = ["list", "read"]
}

path "secret/data/services/simulator/*" {
  capabilities = ["read"]
}
POLICY

bao write auth/approle/role/reef-platform-runtime \
  token_policies="reef-platform-runtime" \
  token_ttl="1h" \
  token_max_ttl="4h" \
  secret_id_ttl="0" \
  secret_id_num_uses="0"

bao write auth/approle/role/reef-simulator \
  token_policies="reef-simulator" \
  token_ttl="2h" \
  token_max_ttl="6h" \
  secret_id_ttl="30m" \
  secret_id_num_uses="1"

echo "OpenBao policy and AppRole bootstrap complete."
echo "Use print-openbao-approle.sh to generate role credentials when needed."

