#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
MIGRATIONS_ROOT="${REEF_MIGRATIONS_ROOT:-$BASE/postgres/migrations}"
POSTGRES_SERVICE="${REEF_POSTGRES_SERVICE:-postgres}"
POSTGRES_USER="${REEF_POSTGRES_USER:-postgres}"
POSTGRES_DB="${REEF_POSTGRES_DB:-reef}"
APP_USER="${REEF_APP_USER:-reef_app}"

# Admin and analytics product state live in their own dedicated Postgres
# containers (postgres-admin, postgres-analytics), not schemas in this DB - see
# D-046. The primary runtime DB still carries the `admin` domain for current
# runtime policy tables such as post-trade profiles until those dependencies are
# separated.
# The arena domain is intentionally applied with the admin DB invocation.
# Override REEF_MIGRATION_DOMAINS/REEF_POSTGRES_SERVICE/REEF_POSTGRES_DB/
# REEF_APP_USER to apply those domains against their own container instead.
domains=(${REEF_MIGRATION_DOMAINS:-runtime auth admin boundary command_log orchestration settlement})

if [[ ! -d "$MIGRATIONS_ROOT" ]]; then
  echo "missing migrations directory: $MIGRATIONS_ROOT" >&2
  exit 1
fi

for domain in "${domains[@]}"; do
  if [[ ! "$domain" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
    echo "invalid schema/domain name in REEF_MIGRATION_DOMAINS: $domain" >&2
    exit 1
  fi
done

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

schema_ddl=""
for domain in "${domains[@]}"; do
  schema_ddl+="CREATE SCHEMA IF NOT EXISTS ${domain};
"
done

run_sql <<SQL
${schema_ddl}
CREATE TABLE IF NOT EXISTS public.reef_schema_migrations (
  migration_id TEXT PRIMARY KEY,
  domain_name TEXT NOT NULL,
  filename TEXT NOT NULL,
  checksum_sha256 TEXT NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
SQL

grant_app_access() {
  local schema_list
  schema_list="$(IFS=,; echo "${domains[*]}")"
  run_sql <<SQL
GRANT USAGE ON SCHEMA ${schema_list} TO ${APP_USER};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${schema_list} TO ${APP_USER};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA ${schema_list} TO ${APP_USER};
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA ${schema_list} TO ${APP_USER};

ALTER DEFAULT PRIVILEGES IN SCHEMA ${schema_list}
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${APP_USER};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${schema_list}
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${APP_USER};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${schema_list}
  GRANT EXECUTE ON FUNCTIONS TO ${APP_USER};
SQL
}

sql_string() {
  printf "'%s'" "${1//\'/\'\'}"
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return
  fi
  echo "missing sha256sum or shasum for migration checksums" >&2
  exit 1
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
    checksum="$(sha256_file "$file")"

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

    if [[ "$migration_id" == "runtime/0069_logged_projection_dirty_queues.sql" ]]; then
      if [[ "${REEF_AUTOMATED_DEPLOY:-0}" == "1" || "${REEF_APPLY_RUNTIME_0069:-0}" != "1" ]]; then
        echo "runtime/0069 requires an explicit quiesced operator rollout; automatic migration is blocked" >&2
        exit 1
      fi
      # Include other Compose projects on this host; a fresh host with no
      # runtime containers requires a separate explicit bootstrap opt-in.
      runtime_containers="$(docker ps -a --filter label=com.docker.compose.service=platform-runtime --format '{{.ID}}')"
      if [[ -z "$runtime_containers" && "${REEF_RUNTIME_0069_FRESH_BOOTSTRAP:-0}" != "1" ]]; then
        echo "runtime/0069 requires a stopped platform-runtime container or explicit fresh-bootstrap opt-in" >&2
        exit 1
      fi
      while IFS= read -r runtime_container; do
        [[ -n "$runtime_container" ]] || continue
        runtime_state="$(docker inspect -f '{{.State.Status}}' "$runtime_container" </dev/null)"
        if [[ "$runtime_state" != "exited" && "$runtime_state" != "created" ]]; then
          echo "runtime/0069 requires platform-runtime to be stopped before migration" >&2
          exit 1
        fi
      done <<<"$runtime_containers"
    fi

    echo "apply $migration_id"
    {
      echo "BEGIN;"
      if [[ "$migration_id" == "runtime/0069_logged_projection_dirty_queues.sql" ]]; then
        # SET LOCAL must stay inside this transaction and psql invocation.
        echo "SET LOCAL lock_timeout = '2s';"
        echo "SET LOCAL statement_timeout = '30s';"
      fi
      cat "$file"
      echo
      echo "INSERT INTO public.reef_schema_migrations(migration_id, domain_name, filename, checksum_sha256)"
      echo "VALUES ($(sql_string "$migration_id"), $(sql_string "$domain"), $(sql_string "$filename"), $(sql_string "$checksum"));"
      echo "COMMIT;"
    } | run_sql
  done
done

grant_app_access
