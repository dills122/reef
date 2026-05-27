# Abuse Breaker Intentional-Trip Lane (2026-05-27)

## Purpose

Validate that the breaker trips under junk-heavy traffic and captures deterministic counters in campaign artifacts.

## Campaign Settings

Command:

```bash
DEV_CAMPAIGN_DURATION=30s \
DEV_CAMPAIGN_RATES=1200 \
DEV_CAMPAIGN_WORKERS=128 \
DEV_CAMPAIGN_TRACE_CHECK_LIMIT=50 \
DEV_CAMPAIGN_INCLUDE_ABUSE_TRIP=1 \
DEV_CAMPAIGN_ARTIFACT_DIR=/private/tmp/reef-throughput-campaign-abuse-lane-20260527-v2 \
node scripts/dev/throughput-campaign.mjs
```

Trip lane runtime config (`abuse-trip`):

- `EXTERNAL_API_ABUSE_BREAKER_MODE=reject-rate`
- `EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS=20`
- `EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS=30`
- `EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS=60`
- `EXTERNAL_API_ABUSE_BREAKER_ROUTES=/api/v1/orders/submit,/api/v1/orders/modify,/api/v1/orders/cancel`

Artifact summary:

- `/private/tmp/reef-throughput-campaign-abuse-lane-20260527-v2/throughput-campaign-summary.json`
- `/private/tmp/reef-throughput-campaign-abuse-lane-20260527-v2/throughput-campaign-summary.md`

## Results

Comparison lanes from same run:

- `quality` (`strict-lifecycle`, `strict-clean`):
  - throughput: `1177.14 rps`
  - accepted throughput: `1078.57 rps`
  - success-rate: `91.63%`
- `capacity` (`capacity-baseline`, `capacity-heavy`):
  - throughput: `1186.42 rps`
  - accepted throughput: `1169.39 rps`
  - success-rate: `98.57%`
- `abuse-trip` (`chaos`, `abuse-trip`):
  - throughput: `1101.14 rps`
  - accepted throughput: `412.86 rps`
  - success-rate: `37.49%`
  - reject taxonomy top codes:
    - `ABUSE_BLOCKED`: `14988` (`72.20%` of failures)
    - `INVALID_STATE`: `5761` (`27.75%` of failures)

Breaker counters captured in lane artifact (`abuseStats`):

- `mode=reject-rate`
- `trips=246`
- `blocks=14988`
- `releases=0` (expected in a short 30s window with 60s block duration)
- `activeBlockedClients=246`

## Interpretation

- Breaker engages quickly and dominates the failure mix under intentionally abusive traffic.
- Counters are now exported per lane in campaign summary, enabling regression checks for trip behavior.
- Normal lanes (`quality`/`capacity`) retain breaker-off behavior and expected success envelopes.

## Follow-up

1. Add a longer intentional-trip lane (5-10m) to validate block-release cycles (`releases > 0`).
2. Add pass/fail assertions for expected minimum `trips` and `ABUSE_BLOCKED` presence in trip lanes.
3. Keep non-tripping overhead lane as separate baseline reference:
   - [`docs/ABUSE_BREAKER_COMPARISON_2026-05-27.md`](./ABUSE_BREAKER_COMPARISON_2026-05-27.md)
