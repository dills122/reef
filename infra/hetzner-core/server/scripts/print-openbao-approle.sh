#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
ROLE="${1:-reef-platform-runtime}"
cd "$BASE"

if [[ -z "${BAO_TOKEN:-}" ]]; then
  echo "BAO_TOKEN must be set to a valid OpenBao token." >&2
  exit 1
fi

bao() {
  docker compose exec -T \
    -e BAO_ADDR=http://127.0.0.1:8200 \
    -e BAO_TOKEN="$BAO_TOKEN" \
    openbao bao "$@"
}

role_id="$(bao read -field=role_id "auth/approle/role/$ROLE/role-id")"
secret_id="$(bao write -f -field=secret_id "auth/approle/role/$ROLE/secret-id")"

cat <<EOF
BAO_ROLE_ID=$role_id
BAO_SECRET_ID=$secret_id
EOF

