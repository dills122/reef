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

platform_host_port="${REEF_BACKBONE_PLATFORM_HOST_PORT:-8080}"
health=""
for _ in $(seq 1 45); do
  if health="$(curl -fsS "http://127.0.0.1:${platform_host_port}/health" 2>/dev/null)"; then
    break
  fi
  sleep 2
done

if [[ -z "$health" ]]; then
  echo "platform-runtime health endpoint did not become ready on port ${platform_host_port}" >&2
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

docker compose exec -T postgres-admin pg_isready -U postgres >/dev/null
echo "postgres-admin: ready"

admin_arena_tables="$(
  docker compose exec -T postgres-admin psql -U postgres -d admin -X -q -t -A \
    -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'arena' AND table_name = 'bots';"
)"
if [[ "$admin_arena_tables" -ne 1 ]]; then
  echo "admin DB arena.bots table is missing" >&2
  exit 1
fi

if [[ -f "$BASE/secrets/platform-runtime.env" ]]; then
  arena_visible_tables="$(
    docker compose exec -T \
      -e PGPASSWORD="${ARENA_POSTGRES_PASSWORD:-}" \
      postgres-admin psql -U "${ARENA_POSTGRES_USER:-admin_app}" -d admin -X -q -t -A \
      -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'arena' AND table_name = 'bots';"
  )"
  if [[ "$arena_visible_tables" -ne 1 ]]; then
    echo "admin_app cannot see arena.bots in admin DB" >&2
    exit 1
  fi
  echo "admin_app visible arena tables: $arena_visible_tables"
fi

docker compose exec -T postgres-analytics pg_isready -U postgres >/dev/null
echo "postgres-analytics: ready"

analytics_export_tables="$(
  docker compose exec -T postgres-analytics psql -U postgres -d analytics -X -q -t -A \
    -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'analytics' AND table_name IN ('simulation_run_exports', 'run_bot_performance_summaries');"
)"
if [[ "$analytics_export_tables" -ne 2 ]]; then
  echo "analytics simulation export tables are missing" >&2
  exit 1
fi

if [[ -f "$BASE/secrets/platform-runtime.env" ]]; then
  analytics_visible_tables="$(
    docker compose exec -T \
      -e PGPASSWORD="${ANALYTICS_POSTGRES_PASSWORD:-}" \
      postgres-analytics psql -U "${ANALYTICS_POSTGRES_USER:-analytics_app}" -d analytics -X -q -t -A \
      -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'analytics' AND table_name IN ('simulation_run_exports', 'run_bot_performance_summaries');"
  )"
  if [[ "$analytics_visible_tables" -ne 2 ]]; then
    echo "analytics_app cannot see analytics simulation export tables" >&2
    exit 1
  fi
  echo "analytics_app visible export tables: $analytics_visible_tables"
fi

echo "resource snapshot:"
snapshot_containers=()
for service in platform-runtime matching-engine postgres postgres-admin postgres-analytics; do
  container_id="$(docker compose ps -q "$service" 2>/dev/null || true)"
  if [[ -n "$container_id" ]]; then
    snapshot_containers+=("$container_id")
  fi
done
if [[ "${#snapshot_containers[@]}" -gt 0 ]]; then
  docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}" \
    "${snapshot_containers[@]}" || true
fi

echo "runtime verification complete"
