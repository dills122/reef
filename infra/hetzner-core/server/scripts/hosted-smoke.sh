#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

RATE="${RATE:-50}"
DURATION="${DURATION:-30s}"
WORKERS="${WORKERS:-16}"
TRACE_CHECK_LIMIT="${TRACE_CHECK_LIMIT:-20}"
REPORT_DIR="${REPORT_DIR:-$BASE/reports/soak}"
RUN_ID="${RUN_ID:-hosted-smoke-${RATE}-${DURATION}-w${WORKERS}-$(date -u +%Y%m%dT%H%M%SZ)}"
REPORT="$REPORT_DIR/$RUN_ID.json"

RATE="$RATE" \
  DURATION="$DURATION" \
  WORKERS="$WORKERS" \
  TRACE_CHECK_LIMIT="$TRACE_CHECK_LIMIT" \
  RUN_ID="$RUN_ID" \
  REPORT_DIR="$REPORT_DIR" \
  ./scripts/run-soak.sh

jq -e '
  (.quality.systemFailureCount == 0)
  and (.quality.validIntentSuccessRatePct == 100)
  and (.traceChecks.fail == 0)
' "$REPORT" >/dev/null

jq '{
  runId: .config.RunID,
  systemFailureCount: .quality.systemFailureCount,
  validIntentSuccessRatePct: .quality.validIntentSuccessRatePct,
  traceChecks: .traceChecks,
  throughputRps,
  acceptedBusinessOpsRps,
  report: $report
}' --arg report "$REPORT" "$REPORT"

echo "hosted smoke passed: $REPORT"
