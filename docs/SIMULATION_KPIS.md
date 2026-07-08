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
- Source: `quality.validIntentSuccessRatePct`
- Formula: `totalSuccess / (totalRequests - invalidIntentRejectCount) * 100`
- Invalid intent reject codes (default): `INVALID_STATE`, `NOT_FOUND`, `SELF_TRADE_PREVENTION`, `VALIDATION_ERROR`
- Meaning: approximates system reliability after removing clearly invalid business intents.

5. `invalid_intent_rate_pct`
- Source: `quality.invalidIntentRatePct`
- Formula: `invalidIntentRejectCount / totalRequests * 100`
- Meaning: signal of simulator/data quality and lifecycle realism.

6. `system_failure_rate_pct` (proxy)
- Source: `quality.systemFailureRatePct`
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

Raw load-tester JSON reports also include a `quality` object with the KPI proxy fields above. Wrapper summaries should read from that object where possible instead of recomputing incompatible definitions.

## Gate Evidence Template

Local and DigitalOcean gates should include this normalized evidence block when reporting throughput or promotion claims. `scripts/dev/lib/report-taxonomy.mjs` exposes `canonicalEvidenceSummary(report)` so wrappers and checks can render the same fields from stress report JSON without inventing per-script names.

```json
{
  "attempted": 0,
  "accepted": 0,
  "directAcked": 0,
  "materialized": 0,
  "projected": 0,
  "lag": 0,
  "p95LatencyMs": 0,
  "p99LatencyMs": 0,
  "rates": {
    "attemptedPerSecond": 0,
    "acceptedPerSecond": 0,
    "directAckedPerSecond": 0,
    "materializedPerSecond": 0,
    "projectedPerSecond": 0
  },
  "gaps": {
    "acceptedToDirectAcked": 0,
    "acceptedToMaterialized": 0,
    "materializedToProjected": 0
  }
}
```

Field sources:

1. `attempted`: `unitMetrics.attemptedCommands`, fallback `totalRequests`.
2. `accepted`: `unitMetrics.acceptedCommands`, fallback `totalSuccess`.
3. `directAcked`: `unitMetrics.directAckedCommands`, fallback `streamDirect.delta.ackedDelta`.
4. `materialized`: `unitMetrics.durableCanonicalCompletedItems`, fallback `venueEventMaterializer.delta.materializedDelta`.
5. `projected`: `unitMetrics.projectedWorkItems`, fallback `streamAckProjector.delta.projectedDelta`.
6. `lag`: `unitMetrics.projectionLagAfter`, fallback `streamAckProjector.delta.afterLag`.
7. `p95LatencyMs`, `p99LatencyMs`: `latencyMs.p95`, `latencyMs.p99`.
8. `rates`: matching `unitMetrics.*PerSecond` values, with legacy throughput fallbacks for attempted/accepted/projected.
9. `gaps`: non-negative differences for accepted to direct-acked, accepted to materialized, and materialized to projected.

Local gate:

```bash
make dev-stress
```

Expected local artifacts:

1. `/tmp/reef-load-report-dev-stress-kpi.json`: includes `evidenceAverages` and per-sample `evidence`.
2. `/tmp/reef-load-report-dev-stress-kpi.md`: includes the `Gate Evidence` table.
3. Per-rate JSON reports: source records for `canonicalEvidenceSummary(report)`.

DigitalOcean gate:

```bash
scripts/dev/do-benchmark-host.sh check
```

Expected DigitalOcean check artifact:

1. `<fetched-run-artifact-dir>/do-benchmark-evidence-summary.json`: per-report normalized evidence from `canonicalEvidenceSummary(report)`.

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
