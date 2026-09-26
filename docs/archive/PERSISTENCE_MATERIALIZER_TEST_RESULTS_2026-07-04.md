# Persistence Materializer Test Results - 2026-07-04

## Scope

This note records the local verification evidence for the venue event batch materialization persistence slice.

The tested architecture was:

```text
HTTP /api/v1 submit/modify/cancel
  -> durable Redpanda command stream ack
  -> matching-engine direct stream consumer
  -> durable VenueEventBatch publication
  -> async platform-runtime materializer
  -> runtime.canonical_venue_event_batches
  -> runtime.canonical_command_outcomes
```

The tests intentionally kept Postgres out of the matching-engine hot path. Postgres was verified as the async compact canonical materialized store after durable event-batch publication.

## Implementation Fixes Required During Test

Two mixed-lifecycle issues were found and fixed before the passing runs:

1. Netty stream-ack routing initially handled `/api/v1/orders/submit` only. `/api/v1/orders/modify` and `/api/v1/orders/cancel` returned `404` until the hot-path route set was expanded.
2. The simulator tracked only `orderId` for lifecycle actions. Modify/cancel commands need the original `instrumentId` for deterministic stream routing, so tracked load-test orders now preserve both `orderId` and `instrumentId`.

Related commits:

- `68dfdf6 fix: route netty stream ack order mutations`
- `08d5a4b fix: route lifecycle actions by tracked instrument`

## Test Shape

Both passing runs used:

- mode: `strict-lifecycle`
- profile: `capacity-heavy`
- transport path: Netty HTTP API to Redpanda stream-ack command publication
- engine path: matching-engine direct Redpanda consumer
- persistence path: platform-runtime venue event materializer consuming durable event batches
- materializer batch size: `1000`
- materializer Kafka max poll records: `1000`
- materializer poll interval: `10ms`
- materializer fetch timeout: `200ms`
- trace checks: disabled for this throughput gate
- DB diagnostics: disabled for this throughput gate
- pass criteria:
  - HTTP success rate at least `95%`
  - no stream-direct failed/nacked/termed/unsupported delta
  - no stream-direct completion gap
  - materializer `failed=0`, `ackFailed=0`, `unsupported=0`
  - Redpanda materializer consumer lag reaches `0`
  - Postgres canonical command outcome count equals accepted command count

Business-level matching rejections were counted as durable command outcomes, not system failures. This is expected for lifecycle modify/cancel commands racing with fills, cancels, or already-terminal order states.

## 5k Mixed Run

Run:

- stream: `REEF_MATERIALIZER_MIX_5K3_COMMANDS`
- event stream: `REEF_MATERIALIZER_MIX_5K3_EVENTS`
- materializer group: `reef-venue-event-materializer-mix-5k3`
- duration: `180s`
- requested rate: `5000 rps`
- workers: `256`
- artifact directory: `/tmp/reef-materializer-mix-5k3-3m`

Load result:

| Metric | Value |
|---|---:|
| Requests | `899950` |
| HTTP success | `899950` |
| HTTP failures | `0` |
| Throughput | `4999.68 rps` |
| p50 latency | `4.87 ms` |
| p95 latency | `14.88 ms` |
| p99 latency | `43.61 ms` |
| Status `202` | `899950` |

Action mix:

| Action | Requests | OK | Failed |
|---|---:|---:|---:|
| Submit | `741512` | `741512` | `0` |
| Modify | `118711` | `118711` | `0` |
| Cancel | `39727` | `39727` | `0` |

Stream-direct result:

| Metric | Value |
|---|---:|
| Fetched delta | `899950` |
| Processed delta | `899950` |
| Published delta | `899950` |
| Acked delta | `899950` |
| Failed delta | `0` |
| Nacked delta | `0` |

Persistence result after catch-up:

| Metric | Value |
|---|---:|
| Materializer lag | `0` |
| Materializer fetched | `24297` |
| Materializer materialized | `24297` |
| Materializer failed | `0` |
| Materializer ackFailed | `0` |
| Materializer unsupported | `0` |
| Canonical event batches | `24297` |
| Canonical batch command total | `899950` |
| Canonical command outcomes | `899950` |

Outcome rows:

| Command type | Result | Count |
|---|---|---:|
| SubmitOrder | accepted | `741512` |
| ModifyOrder | accepted | `45175` |
| ModifyOrder | rejected | `73536` |
| CancelOrder | accepted | `15703` |
| CancelOrder | rejected | `24024` |

## 10k Mixed Run

Run:

- stream: `REEF_MATERIALIZER_MIX_10K1_COMMANDS`
- event stream: `REEF_MATERIALIZER_MIX_10K1_EVENTS`
- materializer group: `reef-venue-event-materializer-mix-10k1`
- duration: `180s`
- requested rate: `10000 rps`
- workers: `384`
- artifact directory: `/tmp/reef-materializer-mix-10k1-3m`

Load result:

| Metric | Value |
|---|---:|
| Requests | `1799951` |
| HTTP success | `1799951` |
| HTTP failures | `0` |
| Throughput | `9999.22 rps` |
| p50 latency | `7.59 ms` |
| p95 latency | `48.65 ms` |
| p99 latency | `92.99 ms` |
| Status `202` | `1799951` |

Action mix:

| Action | Requests | OK | Failed |
|---|---:|---:|---:|
| Submit | `1483279` | `1483279` | `0` |
| Modify | `237490` | `237490` | `0` |
| Cancel | `79182` | `79182` | `0` |

Stream-direct result:

| Metric | Value |
|---|---:|
| Fetched delta | `1799951` |
| Processed delta | `1799951` |
| Published delta | `1799951` |
| Acked delta | `1799951` |
| Failed delta | `0` |
| Nacked delta | `0` |

Persistence result after catch-up:

| Metric | Value |
|---|---:|
| Materializer lag | `0` |
| Materializer fetched | `23632` |
| Materializer materialized | `23632` |
| Materializer failed | `0` |
| Materializer ackFailed | `0` |
| Materializer unsupported | `0` |
| Canonical event batches | `23632` |
| Canonical batch command total | `1799951` |
| Canonical command outcomes | `1799951` |

Outcome rows:

| Command type | Result | Count |
|---|---|---:|
| SubmitOrder | accepted | `1483279` |
| ModifyOrder | accepted | `83313` |
| ModifyOrder | rejected | `154177` |
| CancelOrder | accepted | `28678` |
| CancelOrder | rejected | `50504` |

## Interpretation

This slice is good for the current local persistence milestone:

- The matching-engine hot path remained free of synchronous Postgres writes.
- Durable event batches were published for all accepted commands.
- The async materializer caught up to zero Redpanda consumer lag.
- Compact canonical Postgres rows matched accepted command counts exactly.
- The materializer had no failed, ack-failed, or unsupported records in either passing run.
- Mixed submit/modify/cancel traffic works through the durable command stream and compact canonical persistence path.

This does not prove the downstream UI/read-model projection layer is complete. Normalized `orders`, `trades`, `executions`, `runtime_events`, and UI tables remain downstream projections from compact canonical rows/events. Follow-up work after this result moved the required submit order metadata into the durable event-batch outcome as a compact `acceptedOrder` fact, with the command-payload join kept as a fallback.

## Next Testing Gates

Before promoting this beyond the local persistence slice:

1. Add or run longer bounded soaks, starting with `10k rps` mixed traffic for `10-15m`, then up to a maximum local long soak of about `2h`.
2. Add projection freshness checks once the downstream lifecycle projections are in the tested path.
3. Repeat on the remote benchmark host with DB and Redpanda resource metrics enabled.
4. Add replay/checksum tests that rebuild canonical rows from event batches and compare row counts, payload checksums, command outcome payload hashes, and projection watermarks.
