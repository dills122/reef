# Throughput Campaign Baseline (2026-05-23)

## Purpose

Capture the first two-lane throughput campaign baseline for the new sprint:

- `quality` lane: `strict-lifecycle` + `strict-clean`
- `capacity` lane: `capacity-baseline` + `capacity-heavy`

## Command

```bash
DEV_CAMPAIGN_DURATION=45s \
DEV_CAMPAIGN_RATES=300,500,800 \
DEV_CAMPAIGN_WORKERS=32,64 \
DEV_CAMPAIGN_TRACE_CHECK_LIMIT=100 \
DEV_CAMPAIGN_ARTIFACT_DIR=/tmp/reef-throughput-campaign-baseline \
make dev-throughput-campaign JS_RUNTIME=node
```

## Artifacts

- `/tmp/reef-throughput-campaign-baseline/throughput-campaign-summary.json`
- `/tmp/reef-throughput-campaign-baseline/throughput-campaign-summary.md`
- per-lane stress reports:
  - `reef-quality-rate-<rate>-workers-<workers>.json`
  - `reef-capacity-rate-<rate>-workers-<workers>.json`
- per-lane telemetry:
  - `reef-quality-telemetry.ndjson`
  - `reef-capacity-telemetry.ndjson`

## Goals (Campaign Target)

- success-rate floor: `>= 90%`
- preferred success-rate: `>= 95%`
- minimum throughput target: `1250-1500 rps`
- preferred throughput target: `~2000 rps`

## Results Snapshot

### Quality Lane (`strict-lifecycle`, `strict-clean`)

- peak throughput: `761.72 rps` (`rate=800`, `workers=64`)
- peak accepted: `646.82 rps`
- success-rate range: `84.92%` to `85.31%`
- `>=90%` cap: not reached
- `>=95%` cap: not reached

### Capacity Lane (`capacity-baseline`, `capacity-heavy`)

- peak throughput: `785.46 rps` (`rate=800`, `workers=64`)
- peak accepted: `699.20 rps`
- success-rate range: `88.23%` to `89.20%`
- `>=90%` cap: not reached
- `>=95%` cap: not reached

## Findings

1. Throughput scales with rate/worker increases in both lanes through `800 rps` target, but success-rate remains below `90%`.
2. Failure profile remains dominated by business rejects (`INVALID_STATE`, `NOT_FOUND`), not transport/HTTP failures.
3. Capacity lane outperforms quality lane on accepted TPS, but still does not hit the success-rate floor.
4. Current setup is below the campaign throughput target band (`1250-1500 rps`) for this benchmark shape.

## Next Actions

1. Add stricter lifecycle-valid action gating and stale-state controls to reduce terminal-state modify/cancel attempts.
2. Introduce adaptive per-worker action backoff around repeated terminal rejections.
3. Re-run campaign with expanded matrix (`rates=300,500,800,1000,1250`) after simulator quality controls are tightened.
4. Re-check system hotspots with lane telemetry once success-rate improves and accepted TPS increases.
