#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

# shellcheck disable=SC1091
source "$BASE/secrets/db.env"
# shellcheck disable=SC1091
source "$BASE/secrets/backup.env"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
WORKDIR="$BASE/backups/$TS"
ARCHIVE="$BASE/backups/reef-db-$TS.tar"
ENCRYPTED="$ARCHIVE.age"

mkdir -p "$WORKDIR"

# Ensure unencrypted dump files/archive never linger on disk if a later step
# (encryption, upload) fails after dumps have already been written.
cleanup() {
  rm -rf "$WORKDIR" "$ARCHIVE"
}
trap cleanup EXIT

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

echo "Archiving..."
tar -C "$WORKDIR" -cf "$ARCHIVE" .

echo "Encrypting..."
age -r "$AGE_RECIPIENT" -o "$ENCRYPTED" "$ARCHIVE"

echo "Uploading to R2..."
if command -v aws >/dev/null 2>&1; then
  AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
  AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
  AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
  aws --endpoint-url "$R2_ENDPOINT" \
    s3 cp "$ENCRYPTED" "s3://$R2_BUCKET/db/reef-db-$TS.tar.age"
else
  docker run --rm \
    -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}" \
    -v "$BASE/backups:/backups:ro" \
    amazon/aws-cli:2 \
    --endpoint-url "$R2_ENDPOINT" \
    s3 cp "/backups/$(basename "$ENCRYPTED")" "s3://$R2_BUCKET/db/reef-db-$TS.tar.age"
fi

# Keep local encrypted copies for 14 days.
find "$BASE/backups" -name "*.tar.age" -type f -mtime +14 -delete

echo "Backup complete: reef-db-$TS.tar.age"
