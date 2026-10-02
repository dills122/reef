# Calcify Phase 2 implementation plan

Implementation basis: [bounded experiment report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md). User approved six-slice plan on 2026-09-30. Execution status belongs in WORK_PLAN.md.

1. D-042 matching: run/session/instrument books, submit/cancel/modify/rollback, scope-aware reads and snapshot compatibility. Gate: cross-run isolation and deterministic lane replay; existing Go regression/race suites.
2. Protobuf V1 and pure full-fact resolver: immutable trade and accepted-order facts, identities and exact provenance; acceptance ordering, ID lifetime and duplicate policy. Gate: deterministic wire round-trip plus positive/negative source fixtures.
3. Verified-led managed resolver: Kafka Streams, sequential demand reader, RocksDB index, transactional changelog/output and persistent cursor/target/pending/completed state. Gate: exact real-broker output, crash/replay without duplicate contexts.
4. Lane safety: generation/UUID/retention, bounded bytes/pending/cache, rebalance pause restoration, explicit fault disposition and fencing. Gate: healthy-lane progress during poison, pending restart/reassignment and stale-owner tests.
5. Operations: config/readiness/metrics, changelog and standby recovery, documented recovery objectives. Gate: RF3 broker/node loss, promotion, representative large-state restore/catch-up.
6. Capacity: full-path preflight, short stress and sustained hot/spread/skew/aged-state runs. Gate: 10,000 durable resolved commitments/s, exact reconciliation, bounded lag and recovery headroom. Two fresh orders per trade needs roughly 20,000 successful commands/s upstream.

Each slice includes focused tests, matching contracts/docs, local feature commits and review. No full capacity or availability claim from RF1 fixtures/local-store joins. Legacy post-match remains active; ledger/allocation/clearing/settlement/cutover are separate scope.

## Managed resolver runtime and operation

Select `CALCIFY_STAGE=resolver`; default process remains platform API. Resolver verifies already registered generation/topic UUID through SQL once at startup. Normal resolution uses only source log and local managed state. Source reader uses `read_committed`, one assigned partition, one initial/recovery seek, max16 poll records and16MiB buffered payload. Identity refresh runs at most once per second including idle punctuation; restored source identity is also checked at startup. This detects live recreation; it is not an atomic cross-topic snapshot against malicious simultaneous topic replacement.

Streams `exactly_once_v2` owns input checkpoints, changelog and Protobuf output. Staged input offset can commit while resolution waits: pending verified records, source cursor, full accepted rows, target trade array and completed identities are managed state. Completed frontier is distinct from staged input. Active facts and completed identities have no TTL; run-close/archive pruning needs separate proof. Logical counts are constant-time state counters. Pending queue uses transactional head/tail/next pointers and rejects new lane-order regression; no per-trade index or pending prefix scan. Existing null-key verifier records derive identity from payload; supplied keys must match. Pending checkpoints from pre-release range-based layout require explicit repair if nonempty; no silent reinterpretation.

Default runtime durability is Redpanda RF3 with `write.caching=false`, one standby and one stream thread. `CALCIFY_RESOLVER_BROKER_KIND=KAFKA` instead requires minimum ISR2 and configures changelogs accordingly. Startup checks actual topic settings; canonical source/verified topics must already meet requirements. Resolved topic and changelog creation use explicit backend settings and output/target byte caps. Startup validates reused changelog replication, acknowledgement settings and byte cap as well as canonical/output topics. Local Compose `calcify-phase2` profile explicitly uses RF1 diagnostics; it adds named persistent state volume. Start extractor first to bind registered source UUID, then resolver. Do not change application ID to bypass a fault: that replays history and can duplicate already committed output across application namespaces. Generation must change only with registered source generation transition.

Memory budgets per active task: source record4MiB, source tail16MiB, accepted row64KiB, persistent target/output16MiB, pending200 compact23-byte verifications. Consumer poll limit is at most100 and half pending budget. Producer batches128KiB, linger20ms, LZ4; transaction interval100ms. Output topic/producer byte limits cover configured output budget plus1024B framing. RocksDB block cache32MiB/store, memtables16MiB×2; Streams app cache8MiB. Decoded accepted-row LRU256 entries/4MiB serialized payload per active task; protobuf objects, decoded strings and map overhead add heap above serialized budget (allow roughly8MiB plus object overhead). Eviction falls back to managed point reads; existence/write decisions always use managed state. Multiply store budgets by active and standby task count; native memory and broker client buffers add overhead. Direct byte JSON decode preserves strict UTF-8, with16KiB validation scratch; encoding auto-detection and BOM remain rejected. JSON/protobuf object overhead adds heap above serialized limits. Cursor is never advanced past demanded target. Drain cooperatively yields after200 work units or20ms; a single parse, checksum, broker call or publication can exceed20ms. Broker calls have separate bounded timeouts. Ready trades at current target use same transactional publication directly, without staging queue nodes. Shared canonical checksum retains identical sorted UTF-8 tokens; fixed1536-prefix table and per-call128 shared string-token memo (field names≤128 UTF-8 bytes, values≤32 bytes) avoid repeated framing allocations without retaining input across calls.

Fault disposition: contradictory link/source/acceptance, missing retained prefix, missing target ordinal and ambiguous accepted-ID reuse persist lane fault and stop it. Restart/rebalance preserves fault. Retain already buffered poll suffix in managed state with input offsets and exact key/value envelope. Operator diagnoses immutable source and retained verified records, records audited repair/reconciliation decision, and restores correct namespace from changelog or coordinated generation transition. No automatic skip, latest-fact selection or unsafe fault-clear endpoint. Infrastructure exceptions stop client for supervised restart; uncommitted poll replays. Fault suffix overflow stops client before unsafe checkpoint.

Role `/healthz` reports process state; `/readyz` requires RUNNING and no recorded lane faults; `/metrics` emits bounded per-lane cursor/staged/pending/resolved/index/byte/fault summaries. Bind defaults loopback on8089. Readiness reflects role health, not completion of every accepted commitment. Five-second lane stats allow brief observation delay. Shutdown closes Streams/source readers and preserves state. `dev-down` preserves state volume; destructive reset removes it.

Kafka API basis: [Processor state/changelog](https://kafka.apache.org/43/streams/developer-guide/processor-api/), [EOS/standby configuration](https://kafka.apache.org/43/streams/developer-guide/config-streams/), [consumer pause/rebalance](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html). Guarantees must be qualified against actual production classes and RF3/large-state workloads below; old isolated prototype runs do not sign off this implementation.

Broker acknowledgement basis: [Redpanda topic write caching](https://docs.redpanda.com/streaming/current/reference/properties/topic-properties/) and [Raft quorum replication](https://docs.redpanda.com/cloud-data-platform/get-started/architecture/). Redpanda does not implement Kafka ISR durability semantics. Explicit write caching false requires replica disk flush before acknowledgement; original cached RF3 cohorts are retained with correction in evidence README.

Recovery diagnostic objective:1m full accepted rows, cold local-state loss, same application/changelog, observed RUNNING within120s and exact next-wave reconciliation. One corrected implementation cohort passed19.97s at767MB local state. This objective is a local diagnostic gate, not a production percentile SLO. See evidence ledger for exact cohort and excluded boundaries.


## Nightly qualification limits (2026-09-30)

Current source-generation/source-UUID checks do not bind verified/output UUIDs. Review found expired verified checkpoints may reset to earliest, and verified/output recreation may reuse restored checkpoints/completed identities against different history. Both require fail-closed fixes before merge. Planned remedy: explicit verified checkpoint retention validation with reset disabled and pinned classic consumer protocol; persist generation plus names/UUIDs of source, verified and output topics; reject missing output before creation when application changelog exists; extend existing periodic identity check. Existing source-only checkpoints need explicit repair, since prior input/output UUIDs cannot be inferred. These changes are pending, not current guarantees. Simultaneous destruction of changelog and namespace, and atomic malicious cross-topic replacement, remain outside this guard's proof.

Final local platform regression/coverage passes. Earlier actual HTTP smoke, nine-boundary RF3 fault matrix and1m-row recovery precede final candidate changes. Latest sustained hot cohort has exact3.15m outputs and10,160.50/s active covering rate but fails actual source-delivery duration310.730s versus301s maximum; remaining profiles unrun. Draft cannot merge until review defects and unchanged-candidate qualification close. [Continuation handoff](handoffs/2026-09-30-calcify-phase2.md).

## Run namespace upgrade and coordinated replay (2026-10-02)

Resolver state version 2 uses acceptance keys `O:<generation>:<run UTF-8 byte
length>:<runId>:<order UTF-8 byte length>:<orderId>`. Lane remains store-owned.
`TradeSourceV1.run_id` binds both point lookups to checksum-covered outcome run
metadata, never to whichever acceptance happens to exist. Matching outcome producer
now preserves decoded command run ID for submit/modify/cancel. Legacy submit
records remain readable through their accepted-order run; unscoped legacy modify
trades require audited reconstruction or reconciliation and remain fail-closed.
`LIMIT` and public `LIMIT_HIDDEN` decode to same hidden-limit enum, preserving
original source checksum/provenance and all immutable economics.

Upgrade requires producer rollout before resolver version 2. Empty or already
version-2 state starts normally. A nonempty unversioned/version-1 store faults;
version-2 cannot recover run collisions already collapsed by legacy keys. Preserve
old state/changelog/output and stop resolver/extractor writers before repair.
Inventory source, verified and output UUIDs, offsets, committed contexts and retained
prefixes. Confirm full source and verification history are retained, and identify
legacy unscoped modify trades before replay. If authoritative command records
cannot supply missing run metadata, keep lane stopped and record required repair;
do not manufacture modified canonical history or infer run from order-ID uniqueness.

Operator must approve audited replay plan: replay retained source/verification
history into an isolated version-2 validation namespace and isolated output; compare
commitment identities, complete contexts and already committed downstream effects;
then coordinate production state/checkpoint/output reconciliation under same
registered source generation and source identity. Stop all writers during cutover
and retain rollback copies. Fresh application ID alone is never production repair:
it can duplicate committed output. This change supplies fail-closed version gate,
not an automatic migration/reset tool. No production replay/cutover exercised here.

Regression suite covers interleaved runs with identical IDs, bounded-cache fallback,
pending checkpoint snapshot restoration versus live/fresh replay, duplicate
verification suppression, incompatible-state evidence retention, legacy submit
compatibility and legacy modify rejection. Snapshot test is process-local; broker
EOS/RF3 crash recovery remains separate qualification. Historical source fixture
and prior measurements remain unchanged; current-producer tests explicitly add
known fixture command run metadata and recompute checksum. New
`hidden-run-source-fixture.jsonl` generated from real stream processor submit/modify
commands, with `LIMIT_HIDDEN` preserved in source and canonical enum in contexts.
