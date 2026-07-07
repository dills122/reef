#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

compose=(docker compose -f docker-compose.yml -f docker-compose.stream-ack.yml)

docker compose stop platform-runtime >/dev/null 2>&1 || true

"${compose[@]}" up -d nats matching-engine

stream="${STREAM_ACK_COMMAND_STREAM:-REEF_COMMANDS}"
subjects="${STREAM_ACK_SUBJECT_PREFIX:-reef.cmd.v1}.>"
max_bytes="${STREAM_ACK_COMMAND_STREAM_MAX_BYTES:-1073741824}"
dupe_window="${STREAM_ACK_COMMAND_STREAM_DUPE_WINDOW:-2m}"

if ! "${compose[@]}" run -T --rm nats-box nats --server nats://nats:4222 stream info "$stream" --json >/dev/null 2>&1; then
  "${compose[@]}" run -T --rm nats-box nats \
    --server nats://nats:4222 \
    stream add "$stream" \
    --subjects "$subjects" \
    --storage file \
    --retention limits \
    --discard new \
    --max-bytes "$max_bytes" \
    --dupe-window "$dupe_window" \
    --defaults
fi

"${compose[@]}" up -d \
  platform-api \
  platform-worker-0 \
  platform-worker-1 \
  platform-worker-2 \
  platform-worker-3 \
  platform-projector-0 \
  platform-projector-1 \
  platform-projector-2 \
  platform-projector-3

healthy=false
for _ in $(seq 1 45); do
  if "${compose[@]}" exec -T platform-api curl -fsS http://127.0.0.1:8080/health | jq -e '.status == "ok"' >/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done

if [[ "$healthy" != "true" ]]; then
  echo "platform-api did not report healthy status within timeout" >&2
  "${compose[@]}" ps >&2
  exit 1
fi

"${compose[@]}" exec -T platform-api curl -fsS http://127.0.0.1:8080/health | jq .
"${compose[@]}" exec -T platform-api curl -fsS http://127.0.0.1:8080/internal/stream-ack/health | jq .
"${compose[@]}" ps
