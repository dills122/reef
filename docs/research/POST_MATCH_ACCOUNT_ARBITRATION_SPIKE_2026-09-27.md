# Post-match account arbitration spike — 2026-09-27

Status: retained-fact replay recommendation accepted by project owner on
2026-09-27 as [D-059](../DECISIONS.md#d-059-durable-settlement-admission-order-for-scarce-accounts).
This spike is research, not permission to enable the shadow worker or claim capacity.

## Decision question

How should Reef order settlement decisions for scarce accounts shared by
trades from different canonical source partitions so replay produces the same
outcomes, while disjoint accounts can advance in parallel?

The final independent review of draft commit `29c75e4a` found that sorted
account locks prevent conflicting writes but let whichever partition obtains
the lock first consume scarce cash or securities. The draft worker remains
disabled and has no PR.

## Scope and stop condition

Review retained matching and settlement facts, source ordering, and comparable
ledger implementations. No production, backbone, public-read, droplet, or
capacity change is authorized by this spike. Stop at a decision-ready design;
change the governing contract and code after the owner chooses.

Criteria: deterministic replay; same-lane matching order; atomic four-leg
DvP; failure/retry and source gaps; bounded per-account work; no matching
hot-path database write; full 10k commands/s pipeline qualification with
explicit trade and contention mix. Venue-core 10k does not qualify settlement.

## Evidence and method

### Reef observations

- Committed `runtime.canonical_venue_event_batches` and
  `runtime.canonical_command_outcomes` remain matching authority. Effects have
  `(eventStream, partitionId, streamSequence, effectOrdinal)` identity. Each
  consumer advances a **per-partition** contiguous frontier, with generation
  and coverage evidence. See [canonical effects contract](../work/POST_MATCH_CANONICAL_EFFECTS_CONTRACT_2026-09-26.md).
- `CanonicalStreamPosition.origin(partitionId)` puts partition in high 16 bits
  and `offset + 1` below it. Numeric comparison across partitions establishes
  no matching or business time order.
- `SettlementBoundedTransitionStore.readNextWindow` reads partitions
  independently. `apply` locks only that partition's transition frontier;
  `applyNew` locks touched account rows in sorted key order, then chooses
  `SETTLED` or `BREAK` from available balances. The draft has no durable
  cross-partition arbitration order. This is code observation, not measurement.
- Counterexample: buyer opens with 200 cash; two trades in distinct source
  partitions each need 200 from that account. Whichever partition runs first
  settles; the other breaks. Reversing worker schedule reverses the winner.
- The [throughput baselines](../THROUGHPUT_BASELINES.md) separate venue-core
  and full-projection stages. The [active scaling plan](../work/POST_MATCH_SCALING_IMPLEMENTATION_PLAN_2026-09-26.md)
  requires full-pipeline qualification with trade and accounting rates.

### Comparable implementations and primary sources

| Implementer | Documented behavior | Reef inference |
| --- | --- | --- |
| [Apache Kafka](https://kafka.apache.org/42/streams/core-concepts/) | Partition offsets are processed in order; timestamps may move backward within a partition and arrivals across partitions can be out of timestamp order. Stateful processing must wait/bookkeep when order matters. | `occurredAt`, Kafka timestamp, or encoded position alone cannot prove a safe next decision on an open stream. |
| [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/ARCHITECTURE.md) | Primary chooses request order and commits it in replicated append-only WAL before deterministic execution. Transfers are batched; prefetch is parallel; balance-changing commit loop is sequential. | Make arbitration order a durable fact before decisions; separate ordered decisions from parallel preparation. Reef need not adopt its storage engine. |
| [Modern Treasury](https://docs.moderntreasury.com/ledgers/docs/handle-concurrency) | Balance-conditioned debits need synchronous handling; unconditional hot-account credits can be async/batched. Its [transaction model](https://docs.moderntreasury.com/ledgers/docs/ledger-transactions-overview) groups entries atomically. | A four-leg DvP cannot safely race balance-conditioned legs. Hot-account behavior needs explicit workload and policy. |
| [PostgreSQL](https://www.postgresql.org/docs/current/sql-createsequence.html) | `nextval` is not rolled back, can leave gaps, and cached values can appear out of order across sessions. [Serializable isolation](https://www.postgresql.org/docs/current/transaction-iso.html) gives *some* serial execution, not a source-fixed winner. | Bare `BIGSERIAL` or higher isolation is insufficient as a replay order. |

Right-column conclusions are Reef inferences; those systems do not implement
Reef's exact four-leg cash/security workflow.

## Replay contract and accepted choice

Two meanings of deterministic rebuild differ materially:

1. **Retained-fact replay:** matching facts **plus** immutable, retained
   settlement admission order reproduce outcomes. Rebuilt settlement store
   imports/verifies that order before executing decisions. Fresh admission
   from matching facts alone starts a *new* arbitration history and may choose
   a different scarce-account winner. Recommended for an unbounded live stream.
2. **Matching-source-only recomputation:** matching facts and policy alone
   reproduce the same winner in a fresh database. Today's partitioned source
   supplies no total order. Meeting this requires a new upstream global order
   or provable cross-partition watermark/barrier before each sensitive
   decision. Timestamp sort or bounded lookahead cannot prove absence of a
   later-arriving smaller key.

Under option 1, admission order is canonical post-trade data with retention,
backup, audit, and import duties. An outcome that spends scarce resources
cannot be treated as an ordinary rebuildable projection. Outcomes and four
ledger legs still need atomic recording and exact replay verification.

## Options

| Option | Correctness and failure behavior | Cost/assessment |
| --- | --- | --- |
| Keep per-partition windows and sorted account locks | Avoids double spend; scheduler still chooses winner. Serializable isolation changes retries, not the fixed winner. | Reject. |
| Sort `(occurredAt, partitionId, sequence, ordinal)` | Stable key, but an open idle partition or source gap can later provide an earlier key. Needs upstream barriers/watermarks or ingress sequence. | Reject with current source; likely adds instant-mode latency. |
| Durable global log, strictly serial settlement | Replays exactly with retained log; simple gap/failure proof. | Correct fallback; serial ledger commits may cap throughput. |
| **Durable global log, account-aware parallel settlement** | Rank fixes every conflict. Each admitted window waits for earlier conflicting windows. Four legs and completion commit atomically. | **Accepted**, with retained-fact replay contract. |
| Account lanes without one shared rank | Multi-account DvP queues can disagree about relative trade order; cycle handling reintroduces scheduler choice. | Reject. |
| Record only first-run outcome | Same-generation reads possible, but no independently checkable decision order or exact rebuild contract. | Reject. |

## Recommended correctness model

### Admission

1. Intake validates exact canonical source membership, ownership, and
   per-partition contiguous coverage. Matching acceptance gains no synchronous
   database write.
2. Separate admission transaction takes a bounded, verified obligation window.
   A transactional global counter row assigns one rank per window; trades
   within that window retain source order. The transaction inserts immutable `(rank, source identity,
   source digest, policy version, account-set digest)` facts and immutable
   account membership, then advances admission frontier with coverage evidence.
   Counter, facts, membership, and frontier commit together; rollback leaves no gap.
   Duplicate source identity must have identical rank and digest. `nextval`
   cannot establish this proof.
3. A higher rank cannot commit before a lower rank. Admission checks its
   partition's prior frontier; cross-partition arrival order is intentionally
   recorded, not inferred from timestamps. Rank history and source generation
   survive backup, restore, and migration.

### Execution

4. Window rank `r` is eligible only if no lower unfinished rank shares any of
   its exact account keys or precedes it on the same source partition.
   Disjoint windows on different partitions may execute concurrently. This
   window granularity preserves the existing bounded ledger transaction and
   contiguous execution frontier. It is a throughput tradeoff to measure;
   disjoint trades in one window execute together rather than concurrently.
5. Worker locks eligible window and accounts in stable order and rechecks
   eligibility and digests. Decision, four cash/security legs on success,
   obligation status, account delta/checkpoints, and completion proof commit
   in one transaction. Crash before commit leaves pending work; crash after
   commit yields exact duplicate result. Source gap/conflict stops progress.
6. Completion proofs release dependent windows. No `SKIP LOCKED` shortcut
   may jump over a lower rank sharing a dependency. Bounded claim queries and
   indexes are an implementation gate.

For each account, completed windows form a prefix of admitted rank order, so
each balance check sees exactly that prefix. Disjoint accounts
commute. By induction over rank, the same admission log, fixed opening
positions, and policy yield the same outcomes, postings, and checkpoints
regardless of worker schedule. Fixed openings and absence of hidden
cross-account policy state require explicit code/test proof.

## Feasibility risks and measurement

- Global admission counter is a short serial point per batch. Measure
  admissions/s, lock wait, batch size, and retries. 10k commands/s is not a
  claim of 10k trades/s; include actual ratio and heavy-trade case.
- Scarce hot accounts are inherently serial. Measure skewed shared-cash and
  shared-security cohorts separately from disjoint accounts; report queue age
  and blocking rank.
- Four-account trades form overlapping chains. Prove SQL
  readiness, index bounds, locking order, and retry behavior under concurrency.
- Freeze resource openings and policy version before dependent admission or
  define versioned order. Current account proof detects mutation but does not
  establish a lifecycle contract.
- Admission log cannot be recreated identically from matching facts alone.
  Missing/corrupt ranks or source digests fail closed; a deliberately new
  arbitration generation must be labeled as new history.
- Public reads stay on legacy store until admission, execution, and exact
  coverage watermarks prove new state safe.

## Proposed implementation and proof gate after decision

Deliver one coherent branch/PR replacing draft partition-window transition
algorithm. Commit `29c75e4a` is a starting point for ledger mechanics, not a
merge candidate as written.

1. Update canonical effects/settlement contract, accepted decision, schema,
   worker topology, replay/restore semantics together. Retain original source
   identity in admission facts.
2. Implement batched transactional admission and bounded account dependencies.
   Gate per-partition `applyNew` on predecessor completion;
   preserve atomic four-leg DvP and checkpoint proof.
3. Local PostgreSQL tests: 200 cash/two 200 trades across partitions; opposite
   worker schedules against the *same imported admission log*; disjoint trades
   overlap; three-trade dependency chain; crash before/after commit;
   duplicate/conflicting admission; gaps/rollback; opening/policy mutation;
   export/import replay. Explicitly distinguish matching-only fresh admission
   as a new history.
4. Run migration checks and focused/full platform-runtime tests with local
   Docker. Conduct independent architecture review and fix findings; then open
   PR, apply `ocr-pilot`, read/fix review comments, and let owner merge. No
   public-read cutover in this PR.
5. After merge and functional proof, run integrated 300-second campaign on a
   disposable droplet per active plan. Report commands/s, trades/s,
   admissions/s, ledger facts/s, backlog/freshness by stage, hot-account skew,
   replay correctness, and recovery. A failing result drives measured redesign
   before cutover.

## Owner decision

**Accepted: retained-fact replay and durable admission order with account-aware
parallel execution.** The immutable admission order is canonical post-trade
data retained and imported for deterministic replay. Matching-only fresh
admission is a new arbitration history. Window-level rank is the first
implementation granularity; qualification must measure its lane serialization
and hot-account skew before any public-read cutover.
