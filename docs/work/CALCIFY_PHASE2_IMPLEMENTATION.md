# Calcify Phase 2 implementation plan

Implementation basis: [bounded experiment report](../research/CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md). User approved six-slice plan on 2026-09-30. Execution status belongs in WORK_PLAN.md.

1. D-042 matching: run/session/instrument books, submit/cancel/modify/rollback, scope-aware reads and snapshot compatibility. Gate: cross-run isolation and deterministic lane replay; existing Go regression/race suites.
2. Protobuf V1 and pure full-fact resolver: immutable trade and accepted-order facts, identities and exact provenance; acceptance ordering, ID lifetime and duplicate policy. Gate: deterministic wire round-trip plus positive/negative source fixtures.
3. Verified-led managed resolver: Kafka Streams, sequential demand reader, RocksDB index, transactional changelog/output and persistent cursor/target/pending/completed state. Gate: exact real-broker output, crash/replay without duplicate contexts.
4. Lane safety: generation/UUID/retention, bounded bytes/pending/cache, rebalance pause restoration, explicit fault disposition and fencing. Gate: healthy-lane progress during poison, pending restart/reassignment and stale-owner tests.
5. Operations: config/readiness/metrics, changelog and standby recovery, documented recovery objectives. Gate: RF3 broker/node loss, promotion, representative large-state restore/catch-up.
6. Capacity: full-path preflight, short stress and sustained hot/spread/skew/aged-state runs. Gate: 10,000 durable resolved commitments/s, exact reconciliation, bounded lag and recovery headroom. Two fresh orders per trade needs roughly 20,000 successful commands/s upstream.

Each slice includes focused tests, matching contracts/docs, local feature commits and review. No full capacity or availability claim from RF1 fixtures/local-store joins. Legacy post-match remains active; ledger/allocation/clearing/settlement/cutover are separate scope.
