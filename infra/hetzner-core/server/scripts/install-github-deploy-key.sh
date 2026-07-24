#!/usr/bin/env bash
set -euo pipefail

umask 077

deploy_root="${REEF_DEPLOY_DIR:-/opt/reef}"
authorized_keys="${REEF_DEPLOY_AUTHORIZED_KEYS:-$HOME/.ssh/authorized_keys}"
marker="reef-github-actions-deploy"

[[ "$deploy_root" =~ ^/[a-zA-Z0-9._/-]+$ ]] || {
  echo "REEF_DEPLOY_DIR must be an absolute path without shell metacharacters" >&2
  exit 1
}

IFS= read -r public_key
[[ "$public_key" =~ ^(ssh-ed25519)[[:space:]]+([A-Za-z0-9+/]+={0,3})([[:space:]].*)?$ ]] || {
  echo "expected one ssh-ed25519 public key on stdin" >&2
  exit 1
}

deploy_script="$deploy_root/scripts/deploy-application-services.sh"
[[ -x "$deploy_script" ]] || {
  echo "deploy script is missing or not executable: $deploy_script" >&2
  exit 1
}

key_type="${BASH_REMATCH[1]}"
key_body="${BASH_REMATCH[2]}"
ssh_dir="$(dirname "$authorized_keys")"
mkdir -p "$ssh_dir"
chmod 700 "$ssh_dir"

next_keys="$(mktemp "$ssh_dir/authorized_keys.next.XXXXXX")"
if [[ -f "$authorized_keys" ]]; then
  grep -v "[[:space:]]${marker}$" "$authorized_keys" >"$next_keys" || true
fi
printf 'restrict,command="%s --ssh-command" %s %s %s\n' \
  "$deploy_script" \
  "$key_type" \
  "$key_body" \
  "$marker" >>"$next_keys"
chmod 600 "$next_keys"
mv "$next_keys" "$authorized_keys"

key_file="$(mktemp)"
trap 'rm -f "$key_file"' EXIT
printf '%s %s %s\n' "$key_type" "$key_body" "$marker" >"$key_file"
echo "installed restricted GitHub deploy key:"
ssh-keygen -lf "$key_file"
echo "forced command: $deploy_script --ssh-command"
