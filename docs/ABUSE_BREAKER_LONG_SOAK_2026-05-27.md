# Abuse Breaker Long Soak Validation (2026-05-27)

## Purpose

Validate long-run breaker behavior under intentional abusive traffic, specifically block-release cycling (`releases > 0`) while sustaining throughput.

## Setup

Runtime breaker config:

- `EXTERNAL_API_ABUSE_BREAKER_MODE=reject-rate`
- `EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS=20`
- `EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS=30`
- `EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS=60`
- tracked routes: `/api/v1/orders/submit,/api/v1/orders/modify,/api/v1/orders/cancel`

Soak command:

```bash
node scripts/dev/sim-run.mjs \
  --duration 6m \
  --mode chaos \
  --workers 128 \
  --rate 1200 \
  --submit-pct 35 \
  --modify-pct 45 \
  --cancel-pct 20 \
  --pretty-summary \
  --report-out /private/tmp/reef-abuse-trip-soak-6m-20260527.json
```

## Results

Simulator summary:

- duration: `360.2s`
- throughput: `1084.38 rps`
- accepted throughput: `396.71 rps`
- success-rate: `36.58%`
- status codes: `200=178,528`, `429=212,017`
- reject taxonomy:
  - `ABUSE_BLOCKED=212,017` (`85.61%` of failures)
  - `INVALID_STATE=35,645` (`14.39%` of failures)
- trace checks: `50/50` pass

Breaker counters (`GET /internal/boundary/abuse/stats` post-run):

- `trips=1395`
- `blocks=212017`
- `releases=1156`
- `activeBlockedClients=197`

## Interpretation

- Release cycling is confirmed (`releases > 0`) in sustained traffic.
- Breaker remains dominant in failure classification under junk-heavy load.
- Throughput remains stable at ~1.08k rps while aggressively protecting command path.

## Exit-Criteria Status

For long-soak breaker validation:

- deterministic trip behavior: `pass`
- release-cycle evidence (`releases > 0`): `pass`
- trace integrity under pressure: `pass`
