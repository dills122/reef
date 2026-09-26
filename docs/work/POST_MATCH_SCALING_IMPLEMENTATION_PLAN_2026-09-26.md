# Post-match scaling implementation plan — 2026-09-26

Status: proposed task breakdown, not an accepted ADR, release claim, or parallel
execution board. [WORK_PLAN](../WORK_PLAN.md) remains the status owner. Scope:
qualify sustained 10k commands/s through required live reads while preserving
durable acceptance, deterministic matching, canonical replay, audit completeness,
and atomic accounting. Design basis:
[architecture review](../research/POST_MATCH_PROJECTION_ARCHITECTURE_REVIEW_2026-09-26.md),
[structural seam decision](../research/PROJECTION_STRUCTURAL_SEAM_DECISION_2026-09-25.md),
[throughput ledger](../THROUGHPUT_BASELINES.md), [D-055](../DECISIONS.md#d-055-retry-safe-canonical-projection-batch-claims),
and [issues #360](https://github.com/dills122/reef/issues/360) and
[#367](https://github.com/dills122/reef/issues/367).

## Open-PR reconciliation before a new benchmark baseline

Snapshot from GitHub on September 26. Recheck before merge; status can change.

| PR | Disposition | Required gate |
| --- | --- | --- |
| [#371 — command outcome event IDs](https://github.com/dills122/reef/pull/371), draft | Finish review and land first. It fixes collisions that caused the initial F02 control to stop projecting after 1,021 commands. | Verify same-command replay stability, distinct reject/modify IDs, old recorded event compatibility, and consumers of opaque IDs. Go tests and CI pass on its current head; retain cross-path integration proof. |
| [#372 — LOGGED dirty queues](https://github.com/dills122/reef/pull/372), draft | Land after #371, as a correctness/recovery slice. Keep #367 F02 open for public-read readiness and target rollout proof. | Fix current `postgres-schema-placement` CI failure: `projectionDirtyQueuesAreUnloggedAfterMigration` still expects the old storage class. Re-run full CI, crash/reference test, and migration-lock window on target topology. Record its measured +1.595 GB/+9.04% projection WAL and failed downstream freshness; no 10k claim. |
| [#364 — bounded canonical SQL selector](https://github.com/dills122/reef/pull/364) | Resolve merge conflict and rebase independently. Treat as bounded backlog/query improvement, not the structural 10k fix. | Reconfirm which topology invokes this SQL selector: accepted separate-store projection uses a Kotlin per-partition limit. Preserve gap/duplicate/legacy tests; C45 cloud run was intake-CPU-limited and cannot establish full-pipeline benefit. Do not block lifecycle work on a new cloud run for this PR. |
| [#369 — staged projection worker POC](https://github.com/dills122/reef/pull/369) | Do not merge runtime split as the next architecture. Preserve hosted no-go evidence in repository, then close or reduce to evidence-only changes. | Control passed final counts; split left 4,752 timed projector lag and no authoritative downstream cohort. Resolve six open automated review threads, including candidate/watermark concerns, before any future use of its code. |

Merged [#368](https://github.com/dills122/reef/pull/368) already provides
settlement insert verification, event replay hardening, and bounded public
history reads. Keep its remaining fault and aged-read gates under #367.

## Dependency order and focused slices

### Gate 0 — settle correctness baseline

1. Merge reviewed #371, then fix/review #372 and perform its planned migration
   with projection writers stopped. Decide #364 separately; preserve #369's
   no-go report without activating split mode. Freeze new baseline only after
   exact image, migration set, Compose configuration, and CI are known.
2. Add/finish F01/F03 ambiguous-commit and concurrent semantic-conflict tests;
   prove that no changed immutable fact is acknowledged or silently skipped.
   Prove crash/restart lifecycle and market rows, queue state, and public
   freshness/readiness against full rebuild. Completion of #372 alone does not
   close these gates.

**Checkpoint:** clean CI, exact replay/business reference, failure tests, and
known migration/rollback path. No throughput promotion yet.

### Gate 1 — locate full-pipeline residence

3. Run one fresh, no-profiler C43-shaped control on the settled image: 10k
   offered commands/s for 300s, 64 instruments, recorded materializer/projector
   topology, concurrent reads, fixed observer cadence, and fresh volumes.
   Record accepted/direct-acked/materialized/projected source membership and
   timestamped stage collection. Measure canonical commit, projector SQL/claim,
   lifecycle, market, queue age, PostgreSQL waits/locks/WAL, pool occupancy,
   CPU/I/O, and actual API visibility for the same command cohort.
4. Validate instrumentation perturbation and checker authority with known-bad
   gap, stale-generation, and missing-stage fixtures. Classify elapsed time as
   execution, queueing/lock/I/O wait, or observation delay. Use this attribution
   to choose a single capacity treatment. If lifecycle is minor and normalized
   writes dominate, prioritize a safe write-reduction treatment before Gate 3.

**Checkpoint:** causal bottleneck hypothesis with a frozen matched control;
retain run and corrections in the throughput ledger. No stage-only or postdrain
count is called a 10k pass.

### Gate 2 — ordered operational-effect contract

5. Specify versioned effect identity from canonical batch/partition/sequence
   and ordinal; define submit, reject, modify, cancel, and every maker/taker
   fill transition. Record affected order IDs, source membership, venue/session,
   payload version, dedupe rule, and per-order causal coverage. Test whether
   matching-lane ordering suffices for all touched orders across partitions.
   This contract and read-freshness semantics require an ADR before cutover.
6. Implement pure effect derivation plus state-transition tests against the
   existing full rebuild. Include multi-fill resting makers, terminal numeric
   quantities, duplicate delivery, changed-event conflicts, ownership changes,
   missing sequence, and old payload versions. No production route change.

**Checkpoint:** every business field and trace/execution identity matches the
reference; gaps and semantic conflicts stop progress.

### Gate 3 — shadow state, then a true work-replacement treatment

7. Add isolated versioned shadow state/claim/frontier. Effects, compact state,
   and contiguous progress commit atomically; a duplicate completed claim is a
   no-op. Shadow has one writer per effect set and cannot write the existing
   lifecycle table. Backfill from canonical authority, verify aged/skewed
   reference parity, and rehearse restart/ambiguous commit and rollback.
8. Run the shadow for correctness only. Then conduct a *separate* capacity
   treatment that stops the superseded lifecycle history recomputation for the
   candidate path. Compare against Gate 1's matched control, not against the
   dual-running shadow. Track hot-maker row locks, WAL/rows, and both order and
   market freshness while intake continues.

**Checkpoint:** exact state/replay parity, no new synchronous ingress work, no
unbounded backlog, and measured full-pipeline improvement. If hot-row
contention or write amplification offsets the gain, stop this treatment and
use Gate 1 attribution to choose another lever.

### Gate 4 — cutover and conditional follow-ons

9. Route own-order reads to the new state only with a proved causal coverage
   token and existing authorization/freshness behavior. Preserve old read path
   until its frontier can catch up for rollback. Market snapshots must be based
   on committed operational state; test skewed books before adding a separate
   price-level accumulator or stream. Keep full audit/timeline reads intact.
10. Reconsider independent audit checkpointing only if full-pipeline results
    show audit representation delaying live state after historical dependency
    removal. Account for direct admin/protective events, exact mixed ordering,
    two-stage total WAL/backlog, retention, and read consistency. Test in
    isolated shadows before any physical database move. Consider Kafka Streams
    or incremental SQL only after an input-authority and external-sink recovery
    contract is proved and the one-store approach lacks headroom.

**Checkpoint:** route-by-route parity, rollback drill, and no claim that an
operational-only pass qualifies full audit/history.

### Separate post-trade workstream

11. Replace online whole-run settlement discovery/validation with a durable
    trade/obligation cursor and bounded reads of affected trade, orders,
    obligations, and accounts. Append immutable facts plus complete debit/credit
    accounting and progress atomically. Keep full-run rebuild as reconciliation,
    and preserve realistic and instant profiles under the same state machine.
12. Benchmark trades/s and facts/ledger entries per trade at 1k, 10k, and aged
    histories. Include hot accounts across instruments, failed legs, retries,
    repairs, netting windows, and concurrent projection/API load. Then decide
    whether settlement needs independent physical resources. Do not shard
    accounting by instrument without a cross-account invariant proof.

**Checkpoint:** exact accounting/replay parity, bounded per-transition work,
and independently stated obligation and settlement freshness.

## Final promotion and stopping rule

The final *combined* image must sustain the frozen 10k/300s workload without
growing required-stage backlog and meet conservative command-weighted source to
canonical/lifecycle/market freshness (p95 at most 5s, p99 at most 10s, max at
most 30s), exact source cohort and business reference, zero unexplained
retries/deadlocks, and clean crash/replay. Keep the established three separate
stopped-source fixed-backlog drains at at least 20% measured rate headroom;
also prove in-load freshness, because drain alone is insufficient. Repeat with
warm/aged state, hot-maker and hot-account skew, concurrent reads, and target
migration/rollback. Only then update the promoted baseline. A failed gate
changes the next treatment, not its threshold.
