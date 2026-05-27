# Simulation + App KPI Matrix

This defines the canonical metrics for simulator-driven performance runs so we can separate system behavior from traffic-shape effects.

## Core KPI Definitions

1. `ingress_rps`
- Formula: `throughputRps`
- Meaning: total simulator request rate observed by runtime.

2. `accepted_business_rps`
- Formula: `acceptedBusinessOpsRps`
- Meaning: accepted domain outcomes per second.

3. `end_to_end_success_rate_pct`
- Formula: `totalSuccess / totalRequests * 100`
- Meaning: overall outcome quality across all generated traffic.

4. `valid_intent_success_rate_pct` (proxy)
- Formula: `totalSuccess / (totalRequests - invalidIntentRejectCount) * 100`
- Invalid intent reject codes (default): `INVALID_STATE`, `NOT_FOUND`, `VALIDATION_ERROR`
- Meaning: approximates system reliability after removing clearly invalid lifecycle attempts.

5. `invalid_intent_rate_pct`
- Formula: `invalidIntentRejectCount / totalRequests * 100`
- Meaning: signal of simulator/data quality and lifecycle realism.

6. `system_failure_rate_pct` (proxy)
- Formula: `(totalFailures - invalidIntentRejectCount) / totalRequests * 100`
- Meaning: approximates non-simulator failure contribution.

7. `latency_p95_ms`, `latency_p99_ms`
- Source: `latencyMs.p95`, `latencyMs.p99`
- Meaning: user-facing tail behavior under load.

8. `trace_pass_rate_pct`
- Formula: `traceChecks.pass / traceChecks.checked * 100`
- Meaning: event propagation and trace consistency check.

## Guardrail Targets

1. `end_to_end_success_rate_pct >= 90` (floor), target `>= 95`.
2. `valid_intent_success_rate_pct >= 99` (proxy target).
3. `trace_pass_rate_pct = 100`.
4. Keep `latency_p95_ms` stable while increasing `ingress_rps` and `accepted_business_rps`.
5. `invalid_intent_rate_pct` should fall as persona/lifecycle tuning improves.

## Stress Artifact Outputs

`make dev-stress` now writes:

1. Per-step reports: `/tmp/reef-load-report-dev-stress-rate-<r>-workers-<w>.json`
2. Telemetry: `/tmp/reef-load-report-dev-stress-telemetry.ndjson`
3. Recommendation: `/tmp/reef-load-report-dev-stress-recommendation.json`
4. KPI summary JSON: `/tmp/reef-load-report-dev-stress-kpi.json`
5. KPI summary Markdown: `/tmp/reef-load-report-dev-stress-kpi.md`
6. Optional DB diagnostics directory (when `DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1`):
   - `/tmp/<report-base>-diagnostics/`

## Comparator Usage

Use campaign comparator for before/after tuning:

```bash
DEV_CAMPAIGN_BASELINE_SUMMARY=/tmp/reef-throughput-campaign-baseline/throughput-campaign-summary.json \
DEV_CAMPAIGN_CANDIDATE_SUMMARY=/tmp/reef-throughput-campaign/throughput-campaign-summary.json \
make dev-throughput-compare
```

Comparator output:

1. `/tmp/reef-throughput-campaign-comparator/throughput-campaign-comparator.json`
2. `/tmp/reef-throughput-campaign-comparator/throughput-campaign-comparator.md`
