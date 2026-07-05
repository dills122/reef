#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
MIGRATIONS_ROOT="${REEF_MIGRATIONS_ROOT:-$BASE/postgres/migrations}"
POSTGRES_SERVICE="${REEF_POSTGRES_SERVICE:-postgres}"
POSTGRES_USER="${REEF_POSTGRES_USER:-postgres}"
POSTGRES_DB="${REEF_POSTGRES_DB:-reef}"

domains=(runtime auth admin boundary command_log orchestration analytics)

if [[ ! -d "$MIGRATIONS_ROOT" ]]; then
  echo "missing migrations directory: $MIGRATIONS_ROOT" >&2
  exit 1
fi

run_psql() {
  docker compose exec -T "$POSTGRES_SERVICE" psql \
    -U "$POSTGRES_USER" \
    -d "$POSTGRES_DB" \
    -v ON_ERROR_STOP=1 \
    -X \
    "$@"
}

run_sql() {
  run_psql -q
}

run_sql <<'SQL'
CREATE SCHEMA IF NOT EXISTS runtime;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS admin;
CREATE SCHEMA IF NOT EXISTS boundary;
CREATE SCHEMA IF NOT EXISTS command_log;
CREATE SCHEMA IF NOT EXISTS orchestration;
CREATE SCHEMA IF NOT EXISTS analytics;

CREATE TABLE IF NOT EXISTS public.reef_schema_migrations (
  migration_id TEXT PRIMARY KEY,
  domain_name TEXT NOT NULL,
  filename TEXT NOT NULL,
  checksum_sha256 TEXT NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
SQL

grant_app_access() {
  run_sql <<'SQL'
GRANT USAGE ON SCHEMA runtime, auth, boundary, command_log, admin, orchestration, analytics TO reef_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA runtime, auth, boundary, command_log, admin, orchestration, analytics TO reef_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA runtime, auth, boundary, command_log, admin, orchestration, analytics TO reef_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA runtime, auth, boundary, command_log, admin, orchestration, analytics TO reef_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA runtime, auth, boundary, command_log, admin, orchestration, analytics
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO reef_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA runtime, auth, boundary, command_log, admin, orchestration, analytics
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO reef_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA runtime, auth, boundary, command_log, admin, orchestration, analytics
  GRANT EXECUTE ON FUNCTIONS TO reef_app;
SQL
}

sql_string() {
  printf "'%s'" "${1//\'/\'\'}"
}

for domain in "${domains[@]}"; do
  domain_dir="$MIGRATIONS_ROOT/$domain"
  [[ -d "$domain_dir" ]] || continue

  files=()
  while IFS= read -r -d '' file; do
    files+=("$file")
  done < <(find "$domain_dir" -maxdepth 1 -type f -name '[0-9][0-9][0-9][0-9]_*.sql' -print0 | sort -z)

  for file in "${files[@]}"; do
    filename="$(basename "$file")"
    migration_id="$domain/$filename"
    checksum="$(sha256sum "$file" | awk '{print $1}')"

    existing="$(
      run_psql -q -t -A -c "SELECT COALESCE((SELECT checksum_sha256 FROM public.reef_schema_migrations WHERE migration_id = $(sql_string "$migration_id")), '');"
    )"

    if [[ -n "$existing" ]]; then
      if [[ "$existing" != "$checksum" ]]; then
        echo "checksum mismatch for $migration_id: applied=$existing current=$checksum" >&2
        exit 1
      fi
      echo "skip $migration_id"
      continue
    fi

    echo "apply $migration_id"
    {
      echo "BEGIN;"
      cat "$file"
      echo
      echo "INSERT INTO public.reef_schema_migrations(migration_id, domain_name, filename, checksum_sha256)"
      echo "VALUES ($(sql_string "$migration_id"), $(sql_string "$domain"), $(sql_string "$filename"), $(sql_string "$checksum"));"
      echo "COMMIT;"
    } | run_sql
  done
done

grant_app_access
