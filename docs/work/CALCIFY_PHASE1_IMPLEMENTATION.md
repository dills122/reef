# Calcify Phase 1 implementation and local evidence

Status: additive opt-in diagnostic slice on `codex/calcify-phase1`, based on `origin/master` `56b882d4` (2026-09-29). No production cutover or financial settlement claim. [Discovery](CALCIFY_DISCOVERY.md) owns longer-horizon questions; [link contract](../../contracts/calcify/README.md) owns Phase 1 wire bytes.

## Implemented boundary

- Matching engine still writes committed `VenueEventBatch` to `REEF_VENUE_EVENTS`. Legacy materializer, projections, and trade-to-settlement path remain active.
- Extractor reads `read_committed`, checks semantic checksum, source metadata, outcome membership, and nested trade shape once per batch, then emits one 21-byte pointer per trade to `REEF_MATCH_COMMITMENTS_V1`. It sends output and consumed source offset in one Redpanda transaction; zero-trade batches commit offset with no output.
- Verifier reads links without rereading source. Stub policy version 1 checks wire shape and partition, then transactionally emits 23-byte passing links to `REEF_VERIFIED_COMMITMENTS_V1` with input checkpoint. Pass is not financial or participant approval.
- Receipt worker inserts `runtime.calcify_commitment_receipts` by commitment tuple. Replay keeps one row; conflicting policy version fails. PostgreSQL commit precedes Kafka offset commit. Row presence means recorded only: no `SETTLED` state, cash movement, or securities movement.
- Extractor consumer group includes source generation, so recreated topic starts at its own offset zero after generation advance. Topic UUID is bound in registry at startup and checked again on partition assignment. Malformed source or link pauses affected partition without committing bad offset; other partitions continue. Broker and database failures stop process for restart. Same-partition order follows consumer ownership and explicit output partition.
- `compose.calcify.yml` runs stages as optional local sidecars. Existing default Compose services are unchanged. Runtime selects stage only when `CALCIFY_STAGE` is set.

## Local diagnostic sequence, CAL-P1-L1

Base: C5 venue-core passed two 10,000/s, 300-second samples with projections off; C2 full projection failed sustained 5,000/s. This run uses new Calcify code, one local extractor/verifier/receipt worker, two source partitions, hand-seeded batches, and PostgreSQL receipt counts. No rate or latency comparison to C5/C2 is valid. Historical C5 and C2 artifacts and limits remain in [throughput ledger](../THROUGHPUT_BASELINES.md).

Environment: macOS local Docker 29.7.2, Redpanda v26.2.3, PostgreSQL 16; code from branch worktree with runtime migrations `0070` and `0071`. `docker compose -f compose.base.yml -f compose.local.yml --profile redpanda up -d postgres redpanda`; primary-only forward migrations applied. Docker image build attempt failed fetching Docker Hub base metadata (`DeadlineExceeded`), so `./gradlew installDist` output was mounted into cached `reef-platform-runtime` image for diagnostic. No hosted run or immutable runtime-image digest exists.

Evidence, observed with Redpanda group frontiers and PostgreSQL row queries:

| Step | Source | Result |
| --- | --- | --- |
| Contract and receipt tests | zero/one/many builders; local migrated PostgreSQL | focused Gradle tests pass; receipt replay one row and conflicting policy rejected |
| Assembled path | partition 0 offsets 0–2: 0, 1, 5 trades | six receipts at offsets 1–2; extractor source checkpoint 3, proving zero-trade offset advanced |
| Poison isolation | invalid checksum at partition 0 offset 3; later valid record at 4; valid partition 1 offset 0 | partition 0 checkpoint stayed 3, no row for offset 4; partition 1 checkpoint reached 1 and produced one receipt |
| Seeded verifier and receipt | direct valid commitment link at partition 1 offset 1000; direct passing link at 1001; replay of prior passing link | one receipt for each new link; replay did not add row |
| Fanout | one 9,863-byte source batch with 128 trades at partition 1 offset 1 | 128 receipts with flattened ordinals 0–127; source checkpoint reached 2 |
| Receipt restart | receipt worker stopped; 50 one-trade batches published on partition 1 offsets 2–51; worker restarted | 50 new unique rows, 187 total diagnostic rows, verified backlog drained; corrupt partition 0 remained stopped |
| Final rebuilt path | all workers restarted after producer batching edit; three-trade batch on partition 1 offset 52 | three new rows (ordinals 0–2), 190 total; source checkpoint 53 on partition 1 and 3 on poisoned partition 0 |
| Final receipt and topic identity | receipt worker restarted with reused JDBC connection; seeded verified link at partition 1 source offset 1002; registered topic UUID deliberately mismatched for one extractor startup | one new receipt (191 total); mismatched extractor exited before consumption, registry restored, valid extractor restarted |
| Final registered extraction | checksum-valid one-trade batch at partition 1 offset 53 after guard restoration | one new receipt (192 total); healthy source checkpoint 54, poisoned partition checkpoint 3 |
| Generation-scoped replay | extractor restarted with generation 1 consumer group after group-ID change | healthy partition replayed to checkpoint 54; 192 unique receipts unchanged, poisoned partition checkpoint 3 |

Full-suite correction: first 654-test run failed two pre-existing helper tests because Calcify JSON accessor names shadowed runtime extensions. Accessors were renamed; full suite rerun passed. This failed run is retained as setup evidence, not an application-capacity result.

`rpk group describe` log-end lag includes transaction control records; report relies on source checkpoints and exact receipt identities, not lag field as per-trade latency measure. Fixtures and command output lived under `/private/tmp/calcify-stage/`; retained evidence is this aggregate and committed fixture builders. No sustained capacity, payload-retention, source-to-link independent reconciliation, or source-restore proof was run.

## Full ingress and matching smoke, CAL-P1-E2E

A later local functional run joined the existing HTTP ingress and Go matching
path to Phase 1. `make dev-smoke-calcify-full-path` now creates fresh four-partition
Redpanda command, venue-event, commitment, and verified topics. It uses
PostgreSQL-backed intake and idempotency, direct matching consumption, and the
three Calcify sidecars. It sends a resting buy and a crossing sell in one venue
session, checks two PostgreSQL intake rows, reads committed matching batches,
then checks exactly one receipt for the real trade's broker partition and offset.
The resting batch must produce zero receipts. This is a small functional check;
no rate, latency, retention, or settlement claim follows from it.

Corrections are retained in run order:

| Attempt | Observation | Correction |
| --- | --- | --- |
| Initial manual full path, generation 2, `REEF_CALCIFY_E2E_EVENTS_0929` | Broker stored resting batch on partition 2 while batch declared command lane 1. Extractor stopped partition 2; no full-path receipt. | Sarama producer had default key-hash partitioner despite setting `ProducerMessage.Partition`. Configure manual partitioner; regression test first reproduced partition 2 and then passed with declared partition 1. |
| Manual retry, generation 3, `REEF_CALCIFY_E2E_EVENTS_0929_B` | Broker and batch both used partition 2; resting batch offset 1 had zero trades, crossing sell batch offset 4 had one trade, and extractor produced one link. Verifier failed because receipt consumer had auto-created verified topic with one partition before verifier's four-partition output setup. | Every Calcify stage now provisions and validates both link topics from source partition count before subscribing. Missing source topic fails startup. |
| First reusable smoke, generation 4 | Real trade reached matching and one receipt existed; harness compared `psql` tab-separated row with pipe-separated expected text and failed its own assertion. | Corrected expected delimiter; reran on fresh topics. |
| Reusable direct no-DB smoke, generation 5, `calcify-1790709807245` | Resting batch partition 3 offset 1, zero trades; crossing batch partition 3 offset 4, one trade; receipt `(5,3,4,0,1)` present exactly once. | Pass. This profile used in-memory intake metadata; it did not cover PostgreSQL ingress. |
| PostgreSQL-backed smoke, generation 6, `calcify-1790710028844` | Two command rows in `boundary.stream_command_intake`; resting batch partition 3 offset 1, zero trades; crossing batch partition 3 offset 4, one trade; receipt `(6,3,4,0,1)` present exactly once. | Pass. `make dev-smoke-calcify-full-path` now selects this profile. |
| Final Make entry point, generation 7, `calcify-1790710280736` | `make dev-smoke-calcify-full-path` passed: two PostgreSQL intake rows, resting batch partition 2 offset 1 with zero trades, crossing batch partition 2 offset 4 with one trade, exactly one receipt `(7,2,4,0,1)`. | Pass with final harness including boundary intake assertion. |

Environment: same local macOS Docker 29.7.2, Redpanda v26.2.3, and
PostgreSQL 16 as CAL-P1-L1. Corrected matching and runtime images were built
locally from branch worktree; no hosted or sustained load run. Workload: two
HTTP order submissions, one instrument and venue session, two participants,
one zero-trade batch, one one-trade batch. Observation: committed Redpanda
venue-event metadata and nested trades, PostgreSQL boundary intake rows and
Calcify receipt primary key. The smoke does not start the legacy materializer
or read projections; those remain covered by their separate path tests.

## Run and limits

Start with `compose.base.yml`, `compose.local.yml`, and `compose.calcify.yml`, profiles `redpanda,calcify-phase1`; apply migrations before enabling sidecars. Stage environment has source and output topic names, source generation, and `CALCIFY_AUTO_OFFSET_RESET` (default `earliest`). Output topics are created with source partition count and one replica in this local Phase 1 path; deployment topology and retention need separate review. Source batches lacking `sha256-reef-canonical-v1` stop their partition rather than silently pass.

No archive exists. Before non-diagnostic no-archive run, enforce maximum run duration and post-close replay window; configure source and link topic retention to cover oldest source fact plus processing lag and margin. Define generation advancement on topic recreation, close/window semantics, and source-to-link reconciliation. This is focused follow-up, not claim that temporary receipt path is settlement-ready.
