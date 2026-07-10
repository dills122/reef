#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
ARCHIVE="${1:-}"

if [[ -z "$ARCHIVE" ]]; then
  ARCHIVE="$(find "$BASE/backups" \( -name 'reef-db-*.tar.age' -o -name 'reef-db-*.tar.gz.age' \) -type f | sort | tail -1)"
fi
if [[ -z "$ARCHIVE" || ! -f "$ARCHIVE" ]]; then
  echo "missing encrypted backup archive" >&2
  exit 1
fi

# shellcheck disable=SC1091
source "$BASE/secrets/backup.env"

if [[ -z "${AGE_IDENTITY_FILE:-}" || ! -f "$AGE_IDENTITY_FILE" ]]; then
  echo "AGE_IDENTITY_FILE must point at the private age identity for restore checks" >&2
  exit 1
fi

workdir="$(mktemp -d)"
cleanup() {
  rm -rf "$workdir"
}
trap cleanup EXIT

if [[ "$ARCHIVE" == *.tar.gz.age ]]; then
  age -d -i "$AGE_IDENTITY_FILE" -o "$workdir/reef-db.tar.gz" "$ARCHIVE"
  tar -C "$workdir" -xzf "$workdir/reef-db.tar.gz"
else
  age -d -i "$AGE_IDENTITY_FILE" -o "$workdir/reef-db.tar" "$ARCHIVE"
  tar -C "$workdir" -xf "$workdir/reef-db.tar"
fi

for dump in openbao reef admin analytics; do
  file="$workdir/$dump.dump"
  if [[ ! -s "$file" ]]; then
    echo "missing dump in archive: $dump.dump" >&2
    exit 1
  fi
  pg_restore --list "$file" >/dev/null
  echo "restore-list ok: $dump.dump"
done

echo "restore check complete: $ARCHIVE"
