# HFT Infra Fault Audit

Date: 2026-07-08

## Purpose

Track weak points found during HFT-oriented code audit so fixes can land one by one with tests and benchmark evidence.

This audit focuses on places where Reef can fault, fall over, or silently lose determinism under sustained high-throughput command intake, large books, multi-session workloads, crash/retry behavior, or aged-state operation.

## Fix Order

### 1. Book and Stream Partition Isolation

Priority: P0

Weak point:
- Stream command partitioning uses `runId|venueSessionId|instrumentId`.
- Matching-engine books are keyed only by `instrumentId`.
- Two venue sessions for the same instrument can route to different direct-stream partitions while mutating one shared engine book.

Risk:
- session cross-contamination
- incorrect matches across venue sessions
- direct-stream rollback assumptions can break because same engine book is not lane-isolated
- replay determinism depends on scheduler timing instead of partition order

Target:
- Key engine books by `venueSessionId|instrumentId`.
- Keep order lookup by `orderId` only if order IDs are globally unique, or scope order IDs consistently.
- Add regression tests proving same instrument in different venue sessions does not cross-match.
- Add stream direct tests proving partition/key isolation matches engine book isolation.

### 2. Self-Trade Prevention Full-Book Snapshot

Priority: P0

Weak point:
- `applySelfTradePrevention` calls `Book.Snapshot()` for every incoming order.
- `Book.Snapshot()` copies both book sides and computes checksum.

Risk:
- large resting book turns every incoming order into O(book) CPU and allocation work before matching
- hot symbol can stall even when actual crossing depth is shallow
- snapshot checksum work competes with matching path

Target:
- Replace snapshot-based scan with direct crossing-side iterator over reachable price levels.
- Stop scanning once incoming quantity is exhausted or price no longer crosses.
- Add benchmark/regression for large resting book where non-crossing or shallow-crossing submit remains bounded.

### 3. Direct-Stream Rollback Full-Book Snapshot

Priority: P1

Weak point:
- `BeginBatch` snapshots every touched instrument book before processing each direct-stream batch.

Risk:
- large book plus small batch creates O(book) work per batch
- publish-failure protection can become primary bottleneck
- rollback memory pressure grows with book depth rather than mutation count

Target:
- Replace full snapshots with mutation journal or scoped inverse operations.
- Keep crash gate semantics: if event-batch publish fails, live engine state must return to pre-batch state.
- Add failure-injection test that uses journal rollback and verifies retry produces same canonical outcome.

### 4. Accepted-Async In-Flight Setting Is Ineffective

Priority: P1

Weak point:
- `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE` is clamped to `1`.
- Lane processor submits and completes one command at a time.
- Docs and benchmark notes reference `32` or `64`, but runtime ignores those values.

Risk:
- load profiles can claim a setting that is not active
- lane drain ceiling remains artificially low
- operators tune a dead knob during incidents

Target:
- Either remove the setting from profiles/docs, or implement bounded ordered pipelining.
- Add config test proving `inFlightPerLane` reflects env value.
- Add drain test proving max concurrency per lane is enforced.

### 5. Accepted-Async Status Retention Is Unbounded

Priority: P1

Weak point:
- Queue capacity is bounded, but `recordsByCommandId` and `commandIdByIdempotency` never evict completed records.

Risk:
- long soaks fail by heap even when queue backpressure works
- status map memory grows with total accepted commands, not active commands

Target:
- Add max-entry and/or TTL retention.
- Preserve idempotency replay window according to configured policy or document accepted-async as diagnostic-only.
- Add test that completed entries are evicted without corrupting active status.

### 6. Hot Submit Path DB Read Fanout

Priority: P1

Weak point:
- Submit validation performs command-result lookup, actor-role lookup, full role load, and reference-data validation before engine work in runtime paths.

Risk:
- DB read amplification dominates throughput once write fanout is reduced
- auth/reference tables become global bottleneck
- p99 spikes when pool or Postgres has ordinary control-plane load

Target:
- Introduce cached reference/auth snapshots with versioned invalidation, or move hot-path validation to precompiled in-memory policy state.
- Keep correctness and auditability: cache source, version, and refresh failure behavior must be visible.
- Add tests for stale-cache rejection/refresh behavior.

### 7. Append History Not Physically Partitioned

Priority: P1

Weak point:
- high-volume append tables remain plain tables: command log, canonical venue event batches, canonical outcomes, runtime events, trades.

Risk:
- vacuum, retention, replay, and indexes age into bottlenecks
- aged-state runs behave worse than clean-stack runs
- retention/prune jobs become table-wide operations

Target:
- Land partition plan table by table with migration tests.
- Prefer market date, venue session, run, event stream, or command partition key depending on table.
- Keep active queue tables small and derived.

Progress:
- `command_log.command_results_archive` now provides the first partitioned terminal-history target keyed by `completed_at`.
- `PostgresCommandLogStore.archiveTerminalResults(...)` moves bounded unpinned batches from live results to archive while preserving exact status lookup/accounting; operator scheduling and partition-drop automation are still pending.

## Immediate Work Plan

1. Fix engine book/session isolation and tests.
2. Replace self-trade prevention snapshot scan and add large-book regression/benchmark.
3. Bound accepted-async retention and repair in-flight config behavior.
4. Replace direct-stream rollback full snapshots with mutation journal.
5. Start cached auth/reference validation slice after engine correctness risks are closed.

## Test Expectations

Every fix must include focused tests in the owning service:
- Go matching-engine tests for book/session isolation, self-trade prevention behavior, and rollback semantics.
- Kotlin platform-runtime tests for config, accepted-async retention, and stream-routing behavior.
- SQL/migration tests for partitioning changes.

Throughput-sensitive fixes should include either a benchmark, smoke profile, or documented reason why static tests are enough for the slice.
