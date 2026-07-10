#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

# shellcheck disable=SC1091
source "$BASE/secrets/db.env"
# shellcheck disable=SC1091
source "$BASE/secrets/backup.env"

if [[ -z "${AGE_RECIPIENT:-}" ]]; then
  echo "AGE_RECIPIENT must be set in $BASE/secrets/backup.env" >&2
  exit 1
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"
WORKDIR="$BASE/backups/$TS"
ARCHIVE="$BASE/backups/reef-db-$TS.tar.gz"
ENCRYPTED="$ARCHIVE.age"
R2_BACKUP_PREFIX="${R2_BACKUP_PREFIX:-db/}"
R2_MAX_BACKUPS="${R2_MAX_BACKUPS:-7}"
R2_MAX_BYTES="${R2_MAX_BYTES:-8589934592}"
AWS_CLI_IMAGE="${AWS_CLI_IMAGE:-amazon/aws-cli:latest}"

mkdir -p "$WORKDIR"

# Ensure unencrypted dump files/archive never linger on disk if a later step
# (encryption, upload) fails after dumps have already been written.
cleanup() {
  rm -rf "$WORKDIR" "$ARCHIVE"
}
trap cleanup EXIT

r2_enabled() {
  [[ -n "${R2_ENDPOINT:-}" && -n "${R2_BUCKET:-}" && -n "${AWS_ACCESS_KEY_ID:-}" && -n "${AWS_SECRET_ACCESS_KEY:-}" ]]
}

r2_aws() {
  if command -v aws >/dev/null 2>&1; then
    AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
    aws --endpoint-url "$R2_ENDPOINT" "$@"
  else
    docker run --rm \
      -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
      -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
      -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
      -v "$BASE/backups:/backups:ro" \
      "$AWS_CLI_IMAGE" \
      --endpoint-url "$R2_ENDPOINT" \
      "$@"
  fi
}

r2_list_backup_objects() {
  local output
  if ! output="$(r2_aws s3 ls "s3://$R2_BUCKET/" --recursive)"; then
    return 1
  fi
  awk -v prefix="$R2_BACKUP_PREFIX" 'NF >= 4 {
    key = $4
    for (i = 5; i <= NF; i++) key = key " " $i
    if (index(key, prefix) == 1 && key ~ /reef-db-[0-9]{8}T[0-9]{6}Z[.]tar([.]gz)?[.]age$/) print $3 "\t" key
  }' <<<"$output"
}

r2_upload_backup() {
  local source="$1"
  local target_key="$2"

  if command -v aws >/dev/null 2>&1; then
    AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
    aws --endpoint-url "$R2_ENDPOINT" \
      s3 cp "$source" "s3://$R2_BUCKET/$target_key"
  else
    docker run --rm \
      -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
      -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
      -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
      -v "$BASE/backups:/backups:ro" \
      "$AWS_CLI_IMAGE" \
      --endpoint-url "$R2_ENDPOINT" \
      s3 cp "/backups/$(basename "$source")" "s3://$R2_BUCKET/$target_key"
  fi
}

r2_enforce_budget_before_upload() {
  local incoming_bytes="$1"

  if (( R2_MAX_BACKUPS < 1 )); then
    echo "R2_MAX_BACKUPS must be at least 1" >&2
    exit 1
  fi

  if (( R2_MAX_BYTES < 1 )); then
    echo "R2_MAX_BYTES must be at least 1" >&2
    exit 1
  fi

  if (( incoming_bytes > R2_MAX_BYTES )); then
    echo "encrypted backup is ${incoming_bytes} bytes, larger than R2_MAX_BYTES=${R2_MAX_BYTES}; refusing upload" >&2
    exit 1
  fi

  local listing
  if ! listing="$(r2_list_backup_objects)"; then
    echo "could not list R2 backups; refusing upload so R2 usage cannot drift past budget" >&2
    exit 1
  fi

  local objects=()
  if [[ -n "$listing" ]]; then
    mapfile -t objects < <(printf '%s\n' "$listing" | sort -k2)
  fi

  local total_bytes=0
  local object_count="${#objects[@]}"
  local line size key

  for line in "${objects[@]}"; do
    size="${line%%$'\t'*}"
    total_bytes=$((total_bytes + size))
  done

  while (( object_count + 1 > R2_MAX_BACKUPS || total_bytes + incoming_bytes > R2_MAX_BYTES )); do
    if (( object_count == 0 )); then
      echo "R2 budget cannot fit incoming backup even after deleting older backups; refusing upload" >&2
      exit 1
    fi

    line="${objects[0]}"
    size="${line%%$'\t'*}"
    key="${line#*$'\t'}"

    echo "Deleting old R2 backup to stay under budget: $key (${size} bytes)"
    r2_aws s3 rm "s3://$R2_BUCKET/$key"

    objects=("${objects[@]:1}")
    total_bytes=$((total_bytes - size))
    object_count="${#objects[@]}"
  done

  echo "R2 budget ok: existing=${total_bytes} bytes incoming=${incoming_bytes} bytes max=${R2_MAX_BYTES} bytes backups_after_upload=$((object_count + 1))/${R2_MAX_BACKUPS}"
}

echo "Dumping openbao..."
docker compose exec -T postgres pg_dump \
  -U postgres \
  -Fc \
  openbao > "$WORKDIR/openbao.dump"

echo "Dumping reef..."
docker compose exec -T postgres pg_dump \
  -U postgres \
  -Fc \
  reef > "$WORKDIR/reef.dump"

echo "Dumping admin..."
docker compose exec -T postgres-admin pg_dump \
  -U postgres \
  -Fc \
  admin > "$WORKDIR/admin.dump"

echo "Dumping analytics..."
docker compose exec -T postgres-analytics pg_dump \
  -U postgres \
  -Fc \
  analytics > "$WORKDIR/analytics.dump"

echo "Archiving and compressing..."
tar -C "$WORKDIR" -czf "$ARCHIVE" .

echo "Encrypting..."
age -r "$AGE_RECIPIENT" -o "$ENCRYPTED" "$ARCHIVE"

if r2_enabled; then
  echo "Uploading to R2..."
  encrypted_bytes="$(wc -c < "$ENCRYPTED" | tr -d '[:space:]')"
  target_key="${R2_BACKUP_PREFIX%/}/reef-db-$TS.tar.gz.age"
  r2_enforce_budget_before_upload "$encrypted_bytes"
  r2_upload_backup "$ENCRYPTED" "$target_key"
else
  echo "R2_ENDPOINT/R2_BUCKET/AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY not fully set; keeping encrypted local backup only"
fi

# Keep local encrypted copies for 14 days.
find "$BASE/backups" \( -name "*.tar.age" -o -name "*.tar.gz.age" \) -type f -mtime +14 -delete

echo "Backup complete: reef-db-$TS.tar.gz.age"
