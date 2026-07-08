# Durable Direct Crash Gate Results - 2026-07-08

This note records local evidence for the durable direct path crash/restart gate.

## Command

```sh
JS_RUNTIME=node make dev-smoke-venue-event-crash-gate
```

## Result

- pass
- gate id: `venue-crash-gate-1783472209970`
- command stream: `REEF_CRASH_GATE_COMMANDS_VENUE-CRASH-GATE-1783472209970`
- event stream: `REEF_CRASH_GATE_VENUE_EVENTS_VENUE-CRASH-GATE-1783472209970`
- projection: `runtime-normalized-crash-gate-venue-crash-gate-1783472209970`
- commands checked: 12
- direct-stream partitions covered: 0, 1, 2, 3

## Failure Points Exercised

- materializer and projector stopped while commands were accepted, proving durable event-batch backlog drains after restart
- matching engine stopped while commands were accepted, proving durable command backlog drains after restart
- matching engine event-batch publish failure injected after command fetch/book mutation but before durable `VenueEventBatch` publication, proving rollback, command nak/no offset commit, redelivery, and later accepted publication
- matching engine command ack failure injected after durable `VenueEventBatch` publication, proving command offset commit waits for event-batch publication and redelivery completes without duplicate canonical outcomes
- materializer ack failure injected after compact canonical Postgres commit, proving event-batch offset commit waits for canonical commit and redelivery is idempotent
- projector failure injected after read-model rows commit but before projection watermark commit, proving replay is idempotent and does not advance watermarks over missing work

## Final Counters

- engine final: fetched 4, processed 4, published 4, acked 4, failed 0, ack lag 0
- materializer final: fetched 16, materialized 16, failed 0, ack failed 0, lag 0
- projector final: projected 12, failed 0
- injected engine pre-publish failures observed: 4 failures and 4 nacks across 4 partitions before clean restart
- injected engine ack failures observed: 8 across 4 partitions before clean restart
- injected materializer ack failures observed: 1 before clean restart
- injected projector failures observed: 1, with 12 read-model rows committed before watermark replay

## Replay Report

The replay/checksum gate passed with:

- batch count: 12
- stored command count: 12
- payload outcome count: 12
- canonical outcome count: 12
- stream gaps: 0
- stream overlaps: 0
- missing outcomes: 0
- extra outcomes: 0
- checksum mismatches: 0
- payload hash mismatches: 0
- batch command count mismatches: 0
- duplicate replay inserted: 0

## API Publish-Marker Recovery

The API-side crash window is covered by focused platform tests:

```sh
./gradlew test --tests com.reef.platform.api.PlatformHttpServerBoundaryTest.streamAckRetryAfterPublishAckBeforeMarkerUpdateRepublishesAndConverges --tests com.reef.platform.api.StreamCommandIntakeTest.apiExitsAfterPublishAckBeforeBoundaryMarkerUpdate_retryRepublishesAndConverges
```

The HTTP-path test simulates losing the first boundary published-marker update after a successful stream publish ack. Retrying the same idempotent command republishes once, records the new stream sequence, and later replays return the accepted command reference without publishing again.

## Remaining Scope

This local evidence does not close remote promotion. Remaining scope:

- longer remote soak evidence with attempted, accepted, direct-acked, materialized, projected, lag, p95/p99, restart counts, and artifacts
