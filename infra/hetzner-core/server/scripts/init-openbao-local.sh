#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

SECRETS="$BASE/secrets"
LOCAL_INIT_ENV="$SECRETS/openbao-local-init.env"
LOCAL_INIT_JSON="$SECRETS/openbao-local-init.json"

mkdir -p "$SECRETS"
chmod 700 "$SECRETS"

bao() {
  docker compose exec -T \
    -e BAO_ADDR=http://127.0.0.1:8200 \
    openbao bao "$@"
}

bao_with_token() {
  docker compose exec -T \
    -e BAO_ADDR=http://127.0.0.1:8200 \
    -e BAO_TOKEN="$BAO_TOKEN" \
    openbao bao "$@"
}

status_json() {
  set +e
  local output
  output="$(bao status -format=json 2>/dev/null)"
  local code=$?
  set -e
  if [[ "$code" -ne 0 && -z "$output" ]]; then
    return "$code"
  fi
  printf '%s\n' "$output"
}

wait_for_openbao() {
  local tries="${REEF_OPENBAO_INIT_WAIT_TRIES:-60}"
  local delay="${REEF_OPENBAO_INIT_WAIT_DELAY_SECONDS:-2}"

  for _ in $(seq 1 "$tries"); do
    if status_json >/dev/null; then
      return 0
    fi
    sleep "$delay"
  done

  echo "Timed out waiting for OpenBao status." >&2
  return 1
}

derive_github_repository() {
  if [[ -n "${REEF_GITHUB_REPOSITORY:-}" ]]; then
    printf '%s\n' "$REEF_GITHUB_REPOSITORY"
    return 0
  fi

  local repo_root
  repo_root="$(cd "$BASE/../../.." && pwd)"
  local remote
  remote="$(git -C "$repo_root" remote get-url origin 2>/dev/null || true)"
  if [[ "$remote" =~ github.com[:/]([^/]+/[^/.]+)(\.git)?$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi

  printf 'local/reef\n'
}

write_local_init_files() {
  local init_json="$1"
  printf '%s\n' "$init_json" > "$LOCAL_INIT_JSON"
  chmod 600 "$LOCAL_INIT_JSON"

  local unseal_key root_token
  unseal_key="$(printf '%s\n' "$init_json" | jq -r '.unseal_keys_b64[0]')"
  root_token="$(printf '%s\n' "$init_json" | jq -r '.root_token')"

  cat > "$LOCAL_INIT_ENV" <<EOF
BAO_UNSEAL_KEY=${unseal_key}
BAO_TOKEN=${root_token}
EOF
  chmod 600 "$LOCAL_INIT_ENV"
}

load_local_init_env() {
  if [[ ! -s "$LOCAL_INIT_ENV" ]]; then
    echo "OpenBao is already initialized, but $LOCAL_INIT_ENV is missing." >&2
    echo "Set BAO_TOKEN and BAO_UNSEAL_KEY manually, or reset the local backbone volumes." >&2
    return 1
  fi

  # shellcheck disable=SC1090
  source "$LOCAL_INIT_ENV"
}

unseal_if_needed() {
  local status="$1"
  local sealed
  sealed="$(printf '%s\n' "$status" | jq -r '.sealed')"
  if [[ "$sealed" == "true" ]]; then
    bao operator unseal "$BAO_UNSEAL_KEY" >/dev/null
    echo "OpenBao unsealed."
  else
    echo "OpenBao already unsealed."
  fi
}

wait_for_openbao

status="$(status_json)"
initialized="$(printf '%s\n' "$status" | jq -r '.initialized')"

if [[ "$initialized" == "false" ]]; then
  init_json="$(bao operator init -key-shares=1 -key-threshold=1 -format=json)"
  write_local_init_files "$init_json"
  # shellcheck disable=SC1090
  source "$LOCAL_INIT_ENV"
  status="$(status_json)"
  echo "OpenBao initialized. Local root token and unseal key saved under $SECRETS."
else
  load_local_init_env
fi

unseal_if_needed "$status"

export BAO_TOKEN
export REEF_GITHUB_REPOSITORY
REEF_GITHUB_REPOSITORY="$(derive_github_repository)"

"$BASE/scripts/configure-openbao.sh"

echo "Local OpenBao initialization complete."
