#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

RATE="${RATE:-1000}"
DURATION="${DURATION:-1m}"
WORKERS="${WORKERS:-128}"
MODE="${MODE:-strict-lifecycle}"
BASE_URL="${BASE_URL:-http://platform-runtime:8080}"
SUBMIT_PCT="${SUBMIT_PCT:-60}"
MODIFY_PCT="${MODIFY_PCT:-25}"
CANCEL_PCT="${CANCEL_PCT:-15}"
TRACE_CHECK_LIMIT="${TRACE_CHECK_LIMIT:-100}"
RATE_SCHEDULE="${RATE_SCHEDULE:-drop}"
RUN_KIND="${RUN_KIND:-soak}"
SCENARIO_ID="${SCENARIO_ID:-soak:${RATE}:${DURATION}:w${WORKERS}}"
REPORT_DIR="${REPORT_DIR:-$BASE/reports/soak}"
RUN_ID="${RUN_ID:-soak-${RATE}-${DURATION}-w${WORKERS}-$(date -u +%Y%m%dT%H%M%SZ)}"
TIMEOUT="${TIMEOUT:-30s}"
HTTP_MAX_CONNS_PER_HOST="${HTTP_MAX_CONNS_PER_HOST:-0}"
HTTP_MAX_IDLE_CONNS="${HTTP_MAX_IDLE_CONNS:-4096}"
HTTP_MAX_IDLE_CONNS_PER_HOST="${HTTP_MAX_IDLE_CONNS_PER_HOST:-4096}"
SESSION_CONFIG="${SESSION_CONFIG:-}"
STREAM_ACK_SPREAD_INSTRUMENTS="${STREAM_ACK_SPREAD_INSTRUMENTS:-0}"
COMPOSE_STREAM_ACK="${COMPOSE_STREAM_ACK:-0}"
DIRECT_DOCKER_RUN="${DIRECT_DOCKER_RUN:-0}"
SIMULATOR_IMAGE="${SIMULATOR_IMAGE:-${REEF_SIMULATOR_IMAGE:-reef-simulator:local}}"
DOCKER_NETWORK="${DOCKER_NETWORK:-reef_internal}"
ADMIN_API_BEARER_TOKEN="${ADMIN_API_BEARER_TOKEN:-${ADMIN_API_TOKEN:-}}"
RUN_USER="$(id -u):$(id -g)"

if [[ -z "$ADMIN_API_BEARER_TOKEN" && -f "$BASE/secrets/caddy.env" ]]; then
  while IFS='=' read -r key value; do
    if [[ "$key" == "ADMIN_API_TOKEN" ]]; then
      ADMIN_API_BEARER_TOKEN="$value"
    fi
  done < "$BASE/secrets/caddy.env"
fi

compose=(docker compose)
if [[ "$COMPOSE_STREAM_ACK" == "1" ]]; then
  compose=(docker compose -f docker-compose.yml -f docker-compose.stream-ack.yml)
fi

mkdir -p "$REPORT_DIR"

if [[ -z "$SESSION_CONFIG" && "$STREAM_ACK_SPREAD_INSTRUMENTS" -gt 0 ]]; then
  SESSION_CONFIG="$REPORT_DIR/${RUN_ID}.session.yaml"
  {
    cat <<YAML
session:
  name: stream-ack-submit-stress
  scenarioRunId: $RUN_ID
  seed: 515151
  mode: strict-lifecycle

runtime:
  baseUrl: $BASE_URL
  duration: $DURATION
  workers: $WORKERS
  ratePerSecond: $RATE
  timeout: $TIMEOUT
  traceCheckLimit: $TRACE_CHECK_LIMIT

market:
  timezone: America/New_York
  equities:
YAML
    for i in $(seq 1 "$STREAM_ACK_SPREAD_INSTRUMENTS"); do
      symbol="STK$(printf "%03d" "$i")"
      base=$((100000000000 + i * 1000000000))
      volatility=$((90 + (i % 12) * 10))
      spread=$((4 + (i % 5)))
      cat <<YAML
    - symbol: $symbol
      instrumentId: $symbol
      startingPriceNanos: $base
      avgDailyVolume: 10000000
      sharesOutstanding: 1000000000
      marketCap: $((base * 10))
      volatilityBps: $volatility
      spreadBps: $spread
YAML
    done
    cat <<'YAML'

actors:
  - actorId: stream-mm-01
    actorType: market_maker
    strategyId: two_sided_quote
    weight: 20
  - actorId: stream-inst-01
    actorType: institutional
    strategyId: vwap_slice
    weight: 20
  - actorId: stream-inst-02
    actorType: institutional
    strategyId: tactical_entry
    weight: 20
  - actorId: stream-retail-01
    actorType: retail
    strategyId: dip_buyer
    weight: 20
  - actorId: stream-retail-02
    actorType: retail
    strategyId: passive_limit
    weight: 20

mix:
  actions:
    submitPct: 100
    modifyPct: 0
    cancelPct: 0
  sideBias:
    buyPct: 50
    sellPct: 50
YAML
  } > "$SESSION_CONFIG"
fi
session_config_arg="$SESSION_CONFIG"
if [[ "$SESSION_CONFIG" == "$BASE"/reports/* ]]; then
  session_config_arg="/reports/${SESSION_CONFIG#"$BASE"/reports/}"
fi

report="$REPORT_DIR/${RUN_ID}.json"
stdout="$REPORT_DIR/${RUN_ID}.stdout.json"
stats="$REPORT_DIR/${RUN_ID}.stats.txt"

health_url="${HEALTH_URL:-http://127.0.0.1:8080/health}"
if [[ "$health_url" != "none" ]]; then
  curl -fsS "$health_url" | jq -e '.status == "ok"' >/dev/null
fi

echo "run_id=$RUN_ID"
echo "report=$report"
echo "stdout=$stdout"
if [[ -n "$SESSION_CONFIG" ]]; then
  echo "session_config=$SESSION_CONFIG"
  echo "session_config_arg=$session_config_arg"
fi

docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}" \
  reef-platform-runtime-1 reef-matching-engine-1 reef-postgres-1 > "$stats.before" || true

simulator_args=(
  --base-url "$BASE_URL"
  --duration "$DURATION"
  --workers "$WORKERS"
  --rate "$RATE"
  --rate-schedule "$RATE_SCHEDULE"
  --mode "$MODE"
  --run-id "$RUN_ID"
  --run-kind "$RUN_KIND"
  --scenario-id "$SCENARIO_ID"
  --submit-pct "$SUBMIT_PCT"
  --modify-pct "$MODIFY_PCT"
  --cancel-pct "$CANCEL_PCT"
  --trace-check-limit "$TRACE_CHECK_LIMIT"
  --http-max-idle-conns "$HTTP_MAX_IDLE_CONNS"
  --http-max-idle-conns-per-host "$HTTP_MAX_IDLE_CONNS_PER_HOST"
  --http-max-conns-per-host "$HTTP_MAX_CONNS_PER_HOST"
  --timeout "$TIMEOUT"
  --report-out "/reports/soak/${RUN_ID}.json"
)
if [[ -n "$ADMIN_API_BEARER_TOKEN" ]]; then
  simulator_args+=(--admin-api-bearer-token "$ADMIN_API_BEARER_TOKEN")
fi
if [[ -n "$SESSION_CONFIG" ]]; then
  simulator_args+=(--session-config "$session_config_arg")
fi

if [[ "$DIRECT_DOCKER_RUN" == "1" ]]; then
  docker run --rm \
    --user "$RUN_USER" \
    --network "$DOCKER_NETWORK" \
    -v "$BASE/reports:/reports" \
    "$SIMULATOR_IMAGE" \
    "${simulator_args[@]}" > "$stdout"
else
  "${compose[@]}" --profile manual run --rm \
    --user "$RUN_USER" \
    -v "$BASE/reports:/reports" \
    simulator \
    "${simulator_args[@]}" > "$stdout"
fi

docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}" \
  reef-platform-runtime-1 reef-matching-engine-1 reef-postgres-1 > "$stats.after" || true

{
  echo "before:"
  cat "$stats.before"
  echo
  echo "after:"
  cat "$stats.after"
} > "$stats"

jq '{
  runId: .config.RunID,
  durationSeconds,
  throughputRps,
  acceptedBusinessOpsRps,
  totalRequests,
  totalSuccess,
  totalFailures,
  quality,
  latencyMs,
  traceChecks,
  loadSchedule
}' "$report"

echo "stats=$stats"
