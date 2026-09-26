# Post-match rearchitecture execution plan — 2026-09-26

Status: user-directed implementation direction. Supersedes the earlier
one-lever-at-a-time capacity plan in this file. This is the proposed delivery
map, not a capacity claim. [WORK_PLAN](../WORK_PLAN.md) remains the execution
status owner. Evidence and constraints:
[architecture review](../research/POST_MATCH_PROJECTION_ARCHITECTURE_REVIEW_2026-09-26.md),
[structural seam analysis](../research/PROJECTION_STRUCTURAL_SEAM_DECISION_2026-09-25.md),
[throughput ledger](../THROUGHPUT_BASELINES.md),
[D-055](../DECISIONS.md#d-055-retry-safe-canonical-projection-batch-claims),
and [projection issue #360](https://github.com/dills122/reef/issues/360).

## Decision and target

Build a new post-match dataflow now. Preserve durable ingress acknowledgement,
deterministic Go matching, durable venue-event batches, and compact canonical
PostgreSQL facts. Replace the broad normalized projection dependency chain with
three independently checkpointed consumers of versioned canonical effects:

| Owner | Input and state | Required output |
| --- | --- | --- |
| Live trading | Ordered canonical order and execution effects for both maker and taker orders; dedicated operational PostgreSQL store | Compact current order state, own executions/trades, instrument/session market state, live read API and eventual feed |
| Audit/history | Canonical venue effects plus existing direct admin/protective event authority; existing projection PostgreSQL store | Complete ordered timeline and historical query API, with its own checkpoint |
| Post-trade | Canonical trade effects; dedicated settlement fact-store configuration already supported by runtime | Bounded obligations, workflow facts, atomic ledger/settlement transitions, accounting read API |

Canonical effects are derived deterministically from retained canonical batches
and outcomes. The decoder is one versioned contract used by all consumers; do
not add a new synchronous ingress write. Every consumer commits its own effects
and contiguous progress atomically. A slow audit or settlement consumer must
be visible as lag/failure, without holding up correct own-order state. Full
system qualification still requires every mandatory stage to keep up.

The existing full projector is migration reference and rollback path. New
consumers use isolated tables and checkpoints; they never dual-write the old
effect tables. After parity and route cutover, stop old full projection work
during capacity measurement. Physical resource increase is reported explicitly:
target hosted profile gives operational state its own PostgreSQL instance,
retains the current projection instance for audit, and uses the already
supported separate settlement connection for accounting facts.

## Existing PRs: clear the launch path, not the architecture

Snapshot from September 26; recheck status before action.

| PR | Action |
| --- | --- |
| [#371 event IDs](https://github.com/dills122/reef/pull/371) | Finish review and land first. The collision broke current projection under repeated rejection/modify outcomes and invalidates a clean new baseline. Check replay identity and consumers of opaque IDs. |
| [#372 dirty queues](https://github.com/dills122/reef/pull/372) | Fix obsolete UNLOGGED schema assertion causing current CI failure; review and land after #371. Treat logged queues as crash safety for the legacy path, with its measured +9.04% projection WAL. Keep remaining public-read readiness and rollout checks under [#367](https://github.com/dills122/reef/issues/367). |
| [#364 bounded SQL selector](https://github.com/dills122/reef/pull/364) | Resolve conflict independently if its same-store/backlog query remains useful. Its C45 cloud run was inconclusive; accepted separate-store projection already limits candidates in Kotlin. Do not wait for another selector benchmark before building new flow. |
| [#369 worker split POC](https://github.com/dills122/reef/pull/369) | Retain no-go report and evidence. Do not merge its runtime split as foundation: matched treatment left 4,752 timed projector lag and no authoritative downstream cohort, and review threads remain open. |

Merged [#368](https://github.com/dills122/reef/pull/368) supplies event replay,
settlement insert verification, and bounded public history responses. Keep its
fault follow-ups in #367 without turning them into a prerequisite to writing
the new dataflow.

## Build sequence: cohesive architecture, focused PRs

### Wave 1 — common effect contract and storage ownership

1. Write ADR and versioned canonical-effect contract. Include source batch,
   partition/sequence, effect ordinal, schema version, venue/session/instrument,
   command/event identity, all affected order IDs, and exact trade/execution
   facts. Carry immutable participant/account/currency data needed for bounded
   settlement; where old outcomes lack it, use a keyed canonical order
   directory rather than a run-wide order scan. Define deterministic order,
   gaps, semantic conflict, and replay rules. Prove maker-side effects and any
   cross-partition dependencies. Preserve direct admin/protective audit-event
   provenance as a second durable source.
2. Implement one pure decoder and golden/replay fixtures shared by live, audit,
and settlement consumers. Add isolated operational schema, stage claims,
frontiers, effect dedupe, and causal coverage tokens. Configure dedicated
operational and settlement stores for the target hosted profile while keeping
local Compose usable.

Exit: source-to-effect equivalence, D-055-style atomic progress, versioned
replay and migration contracts. This is an architecture foundation, not a
throughput tuning experiment.

### Wave 2 — replace operational recomputation

3. Implement live projector: apply exact transitions in source order to compact
per-order state, including resting makers. Persist each execution/trade fact
needed by private live reads; coalesce final state writes within a batch.
Current state no longer queries historical timeline or sums all executions.
4. Implement market maintainer from committed live effects. Maintain
instrument/session price-level state and snapshots incrementally; define
snapshot sequence, gap/restart behavior, visibility of hidden orders, and
bounded slow-client policy for a later feed. Route existing live REST reads
only after exact field and authorization parity, with an as-of/coverage token
for combined responses.

Exit: full lifecycle and market business parity on normal, hot-maker,
multi-fill, cancellation, and aged fixtures; duplicate/ambiguous-commit and
crash recovery; route-by-route rollback. Shadow parity is correctness work,
not a capacity score.

### Wave 3 — independent audit and bounded post-trade

5. Give venue audit/history its own claim/frontier and write schedule. Preserve
all venue and direct admin/protective events, trace order, payload retention,
and mixed route semantics. Existing projection PostgreSQL remains direct-event
authority until any source migration has its own lossless cutover. Audit may lag
live state, but lag and missing source are explicit and fail full-system gate.
6. Rewrite trade-to-settlement as bounded transitions: consume canonical trade
effects, create idempotent obligation, advance policy-versioned workflow, and
post complete cash/security ledger legs plus checkpoint atomically. Read only
affected trade, obligation, and accounts. Keep full-run reconstruction as
offline reconciliation, not online append validation. Preserve realistic and
instant simulation profiles through the same commands and state machine.

These two workstreams can proceed alongside live implementation after Wave 1's
contract is fixed. They need their own focused tests, not separate 10k tuning
campaigns. Settle the resource/account partitioning contract before any
cross-instrument ledger sharding.

### Wave 4 — one integrated cutover and capacity campaign

7. Compare all new stages with old reference on a closed cohort; backfill from
canonical facts and direct-event authority, rehearse crash, source gaps,
ambiguous commits, retention failure, and rollback. Switch route adapters by
ownership with explicit freshness metadata. Stop the old full projector in
the treatment image.
8. Run one fresh, matched full-pipeline control/treatment campaign, then the
required warm/aged and skewed qualification. Measure accepted/direct-acked/
materialized, live/order/market/audit/settlement progress, actual same-cohort
API visibility, WAL/rows, locks, CPU/I/O, and backlog slope. Include hot
resting orders, dominant instruments, hot accounts across instruments,
concurrent reads, and settlement-enabled trade mix. Compare hardware and
storage budgets explicitly; added database instances are capacity resources.

Success: sustained 10k accepted commands/s for the frozen 300s workload with
no upward mandatory-stage backlog, exact business/replay/audit/accounting
results, command-weighted lifecycle/market freshness p95 at most 5s, p99 at
most 10s, max at most 30s, crash recovery, and established three-run
stopped-source drain headroom of at least 20%. Drain alone is not in-load
reserve. Also state trades/s and accounting facts/s; command rate cannot
stand in for settlement capacity.

## Execution rules that keep this fast

- Pause new performance micro-optimizations and unrelated venue feature work
  while these architecture waves run. Keep safety/CI fixes moving.
- Focused correctness tests accompany each contract or behavior PR. Do not run
  a cloud A/B after every SQL/index tweak; use one integrated capacity campaign
  after old work is actually replaced.
- If final run misses, inspect stage-level attribution and change a material
  architecture lever: effect fanout, batch/write shape, ownership/partitioning,
  or physical resource placement. Preserve failed evidence and frozen gate.
- Public behavior, event/storage contracts, migration, operational runbook,
  and rollback documentation change with their code. Never acknowledge
  acceptance before durable ingress acknowledgement.
