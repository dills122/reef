#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

if [[ -z "${BAO_TOKEN:-}" ]]; then
  echo "BAO_TOKEN must be set to a valid OpenBao token for bootstrap." >&2
  exit 1
fi

# owner/repo, e.g. "dills122/reef" - required for the GitHub OIDC -> auth/jwt
# backend below, kept separate from the AppRole backend used by runtime reads.
GITHUB_REPOSITORY="${REEF_GITHUB_REPOSITORY:-}"
if [[ -z "$GITHUB_REPOSITORY" ]]; then
  echo "REEF_GITHUB_REPOSITORY must be set (e.g. dills122/reef) for the bot-submission CI auth/jwt backend." >&2
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

# Second, separate auth backend for GitHub Actions OIDC (bot-submission CI
# provisioning), alongside the AppRole backend above used by runtime reads.
# See D-046 in docs/DECISIONS.md - these two backends solve different
# problems (runtime secret reads vs. CI-time provisioning) and must not
# share trust: the jwt role below is scoped to a narrow, write-only-on-bots
# policy, not the read policies granted to reef-platform-runtime/reef-simulator.
if ! bao auth list -format=json | has_json_key "jwt/"; then
  bao auth enable jwt
else
  echo "skip auth jwt/"
fi

bao write auth/jwt/config \
  bound_issuer="https://token.actions.githubusercontent.com" \
  oidc_discovery_url="https://token.actions.githubusercontent.com"

cat <<'POLICY' | bao policy write reef-bot-submission-ci -
path "secret/data/bots/*" {
  capabilities = ["create", "update", "delete"]
}

path "secret/metadata/bots/*" {
  capabilities = ["list", "read", "delete"]
}
POLICY

bao write auth/jwt/role/reef-bot-submission-ci \
  role_type="jwt" \
  bound_audiences="reef-bot-submission-ci" \
  bound_claims_type="glob" \
  bound_claims="repository=${GITHUB_REPOSITORY}" \
  user_claim="actor" \
  token_policies="reef-bot-submission-ci" \
  token_ttl="10m" \
  token_max_ttl="15m"

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

