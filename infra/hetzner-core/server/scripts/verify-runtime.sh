#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

required_tools=(docker jq curl)
for tool in "${required_tools[@]}"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "missing required tool: $tool" >&2
    exit 1
  fi
done

docker compose ps

health=""
for _ in $(seq 1 45); do
  if health="$(curl -fsS http://127.0.0.1:8080/health 2>/dev/null)"; then
    break
  fi
  sleep 2
done

if [[ -z "$health" ]]; then
  echo "platform-runtime health endpoint did not become ready" >&2
  docker compose logs --tail=160 platform-runtime >&2 || true
  exit 1
fi

echo "$health" | jq -e '.status == "ok"' >/dev/null
echo "platform-runtime health: $health"

docker compose exec -T postgres psql -U postgres -d reef -X -v ON_ERROR_STOP=1 \
  -c "SELECT domain_name, count(*) FROM public.reef_schema_migrations GROUP BY 1 ORDER BY 1;"

runtime_table_count="$(
  docker compose exec -T postgres psql -U postgres -d reef -X -q -t -A \
    -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'runtime';"
)"
if [[ "$runtime_table_count" -lt 16 ]]; then
  echo "runtime schema table count is unexpectedly low: $runtime_table_count" >&2
  exit 1
fi

if [[ -f "$BASE/secrets/platform-runtime.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$BASE/secrets/platform-runtime.env"
  set +a
  app_visible_tables="$(
    docker compose exec -T \
      -e PGPASSWORD="${RUNTIME_POSTGRES_PASSWORD:-${RUNTIME_DB_PASSWORD:-}}" \
      postgres psql -U "${RUNTIME_POSTGRES_USER:-${RUNTIME_DB_USER:-reef_app}}" -d reef -X -q -t -A \
      -c "SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('runtime', 'auth', 'boundary', 'command_log');"
  )"
  if [[ "$app_visible_tables" -lt 24 ]]; then
    echo "reef_app cannot see expected migrated tables: $app_visible_tables visible" >&2
    exit 1
  fi
  echo "reef_app visible migrated tables: $app_visible_tables"
fi

echo "resource snapshot:"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}" \
  reef-platform-runtime-1 reef-matching-engine-1 reef-postgres-1 || true

echo "runtime verification complete"
