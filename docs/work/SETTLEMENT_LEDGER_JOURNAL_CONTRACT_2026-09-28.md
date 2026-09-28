# Settlement ledger journal redesign — 2026-09-28

Status: agreed post-match architecture direction and default-off proof contract;
not implemented, capacity-qualified, or approved for public cutover.
Owner: Reef settlement. The PostgreSQL journal is the first candidate to prove,
not a guaranteed capacity result or a decision to keep PostgreSQL regardless of
the complete-path measurements.

The user explicitly permits documented breaking changes during pre-release.
This removes compatibility obligations to the current shadow settlement schema;
it does not relax durable acceptance, financial finality, conservation,
deterministic replay, source authority or recovery checks.

## Agreed authority and scope

This rework starts **after** the existing matching engine emits and commits its
D-058 versioned outcome. Go matching, matching-sensitive command order, ingress
acceptance, pre-trade controls and the matching-outcome format are out of scope.
If the existing outcome cannot support a required proof, report that constraint;
do not change matching as an implicit fix. Simulators and manual users continue
through the same existing command/API paths.

| Point | Authority and meaning | Settlement dependency |
| --- | --- | --- |
| Durable `202 Accepted` | Configured ingress mechanism accepted the command; not an execution or settlement result | None; do not weaken durable-ack semantics |
| Committed D-058 matching outcome | Trade execution, parties, price, quantity and identities are final, subject only to an explicit correction/bust contract | Does not wait for settlement |
| Committed ordered settlement-journal result | Financial transfer or typed failure for an attempted settlement transition is final | Defines settlement finality; never decides whether the trade existed |
| Projection frontier | Bounded, rebuildable view of one of those authorities | Must disclose its source/as-of position |

The instant-post-trade profile may target same-tick happy-path settlement, but
settlement completion is **not** a condition for an executed trade to exist or
be reported. The `ops-realistic` profile can retain its T+1 default, while
`instant-post-trade` remains a near-instant target; this redesign imposes no
regulatory waiting period. D-050's same-tick language needs this clarification at cutover;
D-059's separate admission transaction and canonical per-window checkpoint
rows are replaced only after the journal path passes its complete proof. The
currently deployed/shadow behavior remains unchanged until then.

Pre-trade balance checks, holds, reservations or credit limits may lower the
chance of failure, but a check alone cannot prevent concurrent overspend and
none makes post-match verification optional. Designing stronger pre-trade
controls is a separate task, not permission to alter matching in this one.

## Agreed post-match flow

```text
Committed matching outcome
  +--> market-data projection --> book, depth, trade tape APIs
  +--> verified post-match input --> ordered settlement evaluation
                                  --> atomic journal result
                                  --> balance, history, settlement-status APIs
```

1. Consume the committed outcome with exact stream, generation, partition,
   sequence, effect ordinal and bytes. Check contiguous coverage and identity;
   reject gaps, conflicting replay or unsupported versions. Decode once into
   a logical obligation, not another authoritative trade row.
2. Bind ordered, immutable policy activation, opening-resource and later
   funding/repair facts to that input. Current first-consumer reads of mutable
   policy/resource tables cannot stand in for historical replay authority.
3. Assign deterministic settlement order across affected accounts and source
   partitions. One ordered owner initially evaluates a bounded batch against
   its committed in-memory account state. An earlier committed journal result
   wins scarce resources; worker scheduling never chooses the winner.
4. Recheck both sides against settlement state through the committed journal
   frontier. Upstream risk controls are confidence, not financial proof. Do not
   do per-trade SQL balance scans in the decision loop.
5. Atomically append the ordered typed result set and fenced head. Success
   records exact four-leg gross DvP effects: buyer cash down, seller cash up,
   seller security down, buyer security up. No partial transfer is visible.
   Journal commit is settlement finality; tentative in-memory state becomes
   live only after that commit is confirmed.
6. If delivery cannot complete, append an ordered typed failed attempt with
   **no asset transfer**. The trade remains executed and its obligation remains
   outstanding. A later explicit funding/repair input may cause a new ordered
   attempt; replay of the original trade cannot invent a different first
   outcome. A result is per attempted transition, not a claim that one trade
   can have only one attempt forever.
7. Project balances, trade/settlement history and statuses after commit. Views
   are rebuildable and disclose their as-of source or journal frontier. A
   required current private balance waits within a bounded budget or fails
   closed; a lagging view must not claim to be current.

An executed trade therefore progresses semantically through `EXECUTED` with
settlement pending, then a settled transfer or a failed/outstanding attempt.
These are semantic states, not final public enum names. A settlement failure
does not cancel the trade or remove it from trade tape. Only a separately
specified matching correction/bust can alter the execution record.

## Why replace the current shape

The PM-S3 treatment accepted 9,254 commands/s but completed only 2.06–2.56
settlement windows/s against about 110 windows/s required at the observed
trade mix. Its admission counter and predecessor readiness were independent
bottlenecks. In a local 640-trade hot cohort, normalized execution wrote 640
attempts, 2,560 ledger legs and 2,560 account checkpoints; a typed-result
candidate wrote 1,280 result rows and still 2,560 checkpoints. Hot execution
took 415–566 ms against a 145.45 ms workload-derived budget, and even the
zero-cost fact-writer lower bound remained 199–345 ms. These are different
environments and stages, not one end-to-end latency claim. See
[PM-S3](../THROUGHPUT_BASELINES.md#pm-s3-attempt-3--corrected-settlement-db-limit-treatment-capacity-failed)
and `codex/pm10-vertical-continuation@386d4add` at
`docs/research/evidence/pm10-settlement-local-2026-09-28/carried-input-continuation.md`.

The current intake, obligation, seal, admission, transition, checkpoint and
read-model tables repeat or revalidate facts across several PostgreSQL
transactions. A late child insert after an input seal proved the current
target rows cannot be trusted as immutable authority. Replacing row encoding
alone does not remove those authorities or proof reads.

The 415–566 ms observation is for a **640-trade ordered hot cohort**, not one
user API response. The 9,254 commands/s PM-S3 acceptance rate is also not
settlement completion: accepted work outran mandatory post-match work. Keeping
settlement off the trade-response dependency avoids waiting for settlement,
but that logical decoupling alone does not lower compute or sustain throughput;
an ever-growing journal/projection backlog still fails. The expected physical
gain comes from removing repeated proof reads and synchronous derived writes,
then proving the whole path under load. No component timing can be reported as
the requested roughly 50 ms response or as completed settlement capacity.

| Must remain durable or exactly provable | Move off the financial commit path |
| --- | --- |
| Existing matching source identity and exact outcome bytes; ordered policy, opening, funding and repair inputs | Duplicate target-side trade intake and obligation copies used as additional authorities |
| Fenced journal batch order, immutable typed transition results, four-leg conservation and exact source coverage, including empty ranges | Separate rank/dependency transaction, attempt/status rows, four separate hot ledger-leg inserts and per-window account checkpoints |
| Replayable intermediate account states, scarce-resource winners, original workflow facts, verified snapshots and restore chain | Synchronous balance/history/status read-model writes; projections checkpoint and rebuild independently |

This is a logical-fact preservation rule, not permission to omit audit data.
Periodic snapshots shorten replay but do not replace immutable ordered results.

## Decision and alternatives

Choose a **single-writer, PostgreSQL-backed append-only settlement journal**
for the first complete vertical proof. One ordered owner evaluates a bounded
batch against in-memory account state, then atomically appends one batch-order
record and one narrow, typed result per financial transition, and advances a
fenced journal head. The append is the financial finality point. No per-trade
obligation-status update, separate ledger-leg insert, per-window
account-checkpoint row or public read-model write occurs in that transaction.
These are reconstructed from the journal into independently checkpointed
projections. Keep native keys and local conservation checks on the typed
result; do not substitute an opaque batch blob merely to lower row count.
The journal remains PostgreSQL-backed initially to use Reef's existing
local/hosted durability, backup and operations path; this is not an assertion
that it will meet 10k/s.

| Option | Decision now | Reason |
| --- | --- | --- |
| Retune existing normalized/typed tables | Reject | Measured non-fact and fact work both miss the hot-chain budget; layout swaps have not closed it. |
| TigerBeetle as immediate authority | Defer | Its batched ledger and linked transfers fit balance decisions, but Reef still needs a durable cross-partition rank, policy/funding inputs, workflow result, source frontier and recovery protocol across two stores. A second commit authority is not a drop-in fix. |
| Kafka topic alone as financial authority | Defer | The current Kotlin/SQL workflow has no proved atomic source-offset, finance-state and result-output transaction in that topology. A log can be the authority only after that protocol is specified and fault-tested. |
| PostgreSQL typed journal with in-memory evaluator | Prove first | One append transaction with one typed result per transition can replace several synchronous representations without adding a new consensus system. Its WAL, indexes and single-owner ceiling must be measured, not assumed. |
| Netting or delayed settlement | Separate product choice | Reduces financial operations but changes the current instant gross-DvP winner/finality contract. |

This is an inference from Reef measurements and primary descriptions of
[TigerBeetle](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/ARCHITECTURE.md),
[LMAX](https://martinfowler.com/articles/lmax.html),
[Kafka Streams](https://kafka.apache.org/43/streams/developer-guide/memory-mgmt/)
and [FoundationDB](https://www.foundationdb.org/files/fdb-paper.pdf), not a
throughput guarantee transferred from them. A longer comparison is retained
in `codex/pm10-vertical-continuation@386d4add` at
`docs/research/POST_MATCH_10K_ARCHITECTURE_DECISION_REVIEW_2026-09-28.md`.

## First logged-write falsifier

On 2026-09-28, `SettlementJournalWriteShapeProofTest` ran against the local
dedicated `reef-settlement-postgres` container, PostgreSQL 16.15, on Darwin
25.6.0 arm64. PostgreSQL reported `synchronous_commit=on`, `fsync=on` and
`full_page_writes=on`. Source checkout was `646987cd` plus the uncommitted
proof test; the test creates and drops an isolated, permanent logged schema.
Command: `cd services/platform-runtime &&
SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef
SETTLEMENT_POSTGRES_USER_TEST=reef
SETTLEMENT_JOURNAL_PROOF_BENCH=1 ./gradlew test --offline --console=plain
--tests com.reef.platform.infrastructure.persistence.SettlementJournalWriteShapeProofTest`.
Both tests passed with no skips.

Six warm 640-result append samples were 26.55–28.42 ms, median 27.69 ms;
the separately timed in-memory four-balance decision loop was 0.47–0.70 ms.
The database produced about 440–441 KB WAL per batch. The proof writes one
header, 640 typed results and one fenced head update in one transaction, with
native keys and a scarce-resource winner/duplicate check. This is an
**observation of only the write shape**: its synthetic workflow strings were
shorter than Reef's exact workflow facts, and it had no source read or verified
coverage, policy/funding log, digest chain, snapshot/replay, projection,
failover, restore or concurrent sustained input.

A second run replaced the fixture with 160 touched source accounts and full
11-stage workflow strings shaped like the current transition store's facts.
Its six warm append samples were 25.82–30.52 ms, median 27.58 ms, with
about 989 KB WAL per batch; evaluation was 0.71–1.05 ms. Both tests again
passed with no skips. The fixture still omits exact source binding, recovery,
projection and sustained concurrency, so neither run meets or fails the
10k/s or 145.45 ms end-to-end gate. Those missing costs are the next proof,
not an invitation to extrapolate this microbenchmark.

A third run added length-delimited result hashing, chained batch/head digests,
duplicate-commit comparison, ordered replay verification, tamper detection,
pre-commit rollback injection and owner-epoch takeover. The first invocation
failed because the test's injected third result was still `PENDING`, which the
database correctly rejected before the injected crash. After correcting that
fixture to `SETTLED`, both tests passed. Six warm append samples were
32.12–37.07 ms, median 36.10 ms, with about 989–990 KB WAL per batch. This
run still omits source/policy/funding binding, real owner lease/incarnation,
snapshot and public projection. The varying warm times are not a stable
capacity rate; the prior runs remain recorded rather than replaced.
The follow-up retry test also verifies stored result rows before accepting an
ambiguous duplicate; six warm first-append samples were 28.53–31.39 ms.
[Raw run ledger](../research/evidence/settlement-journal-write-shape-2026-09-28.md)
retains all invocations and the failed-fixture correction.

## Authority and record

1. D-058 versioned venue outcomes remain matching source authority. Every
   ledger input identifies the exact source stream, generation, partition,
   sequence and effect ordinal. A committed source position is consumed once;
   gaps or changed bytes fail closed. No new synchronous ingress/matching write.
2. Policy activation, opening resources and later funding become ordered,
   immutable inputs before public cutover. The existing first-consumer
   observation of mutable control-plane rows is insufficient for deterministic
   historical replay. The initial local proof may freeze one policy/opening
   fixture, but it cannot qualify public settlement on that restriction.
3. A batch header contains schema version, stream/generation, owner epoch,
   batch sequence, previous-batch digest, exact source position ranges/member
   digests and a digest of ordered results. Empty source windows retain
   explicit coverage. Each immutable typed result carries its order within
   the batch, bound policy/opening versions, exact trade input, transition
   ordinal, result kind, typed break reason, original workflow bytes/stages,
   and for success the four exact account/asset/amount effects. Native keys
   reject duplicate identities; local checks reject partial/unbalanced legs.
   A deterministic digest binds every result to the header and predecessor.
4. One journal-head row per ledger stream records next batch sequence, last
   digest and fencing epoch. In one PostgreSQL transaction, the writer locks
   that head, checks expected sequence/digest/epoch, inserts one bounded
   header and its exact typed result set, advances the head and commits. A
   failed append publishes no financial result. Group size has trade, byte,
   account and age limits; the 640-trade hot fixture is a falsifier, not a
   production batch default.
5. The owner computes tentative decisions from prefetched account state
   without SQL in the per-trade loop. It swaps tentative state into the live
   state only after durable append. On ambiguous commit, it reads the unique
   sequence/digest before deciding whether to retry. On restart or takeover,
   it loads a verified snapshot and replays journal envelopes in order.
6. `(batch_sequence, result_index)` is the durable scarce-account arbitration
   order. One owner initially assigns it across source partitions, removing
   the separate contended rank-counter transaction. Uncommitted candidate
   order is never exposed or acknowledged. A break is itself an ordered,
   committed result. Independent account components may be sharded only after
   a deterministic cross-component ordering and recovery proof; no
   scheduler-timing winner is allowed. This explicitly amends D-059's
   separate pre-decision admission step after the vertical proof passes.
7. Every intermediate account balance, four-leg view, obligation status and
   per-trade history is logically reconstructable from opening/funding inputs
   plus ordered journal results. Periodic state snapshots and SQL read tables
   are rebuildable, not additional decision authorities. The current D-059
   requirement for canonical **per-window checkpoint rows** must be amended;
   exact intermediate states and their digest proofs remain required on replay.
8. A public settlement read carries an as-of journal position. Financial
   finality is the journal commit, not the projection arrival. If a required
   read has not caught up to the committed position, it waits within a bounded
   freshness budget or fails closed; it never presents an older balance as
   current. Projection write cost and lag remain in the whole-pipeline gate.

## Independent market-data path and cutover gate

The committed D-058 matching outcome fans out to **sibling** consumers:
market-data projection serves book, depth and trade tape; settlement journal
serves balances and settlement-status reads. Neither waits for the other.
Execution can appear on the trade tape while settlement is pending. A later
settlement failure must not remove or rewrite that executed trade. Keep price,
book and execution facts sourced from matching outcomes; private balances and
available assets are journal-positioned settlement facts.

Market-data queries must use bounded, indexed projections, not scan the
settlement journal or rebuild the book per request. Market projection and
settlement append/replay need independent checkpoints and bounded connection
budgets. Logical separation is insufficient if shared source reads or physical
database contention make either path lag; measure both under one workload.
If client polling makes per-request database reads too costly, evaluate cached
snapshots or a projection-fed stream as a measured read-delivery choice; neither
changes matching or makes the settlement journal a market-data source.
The current market-data worker's default poll cadence is 250 ms. Snapshot and
depth expose lag metadata; trade tape needs an equally explicit source/as-of
frontier before cutover. API response time and source-to-visible age are
separate measures: a quick response with stale market facts does not pass.

Freeze numeric market API p95/p99 response and source-to-visible age limits
before the integrated qualification run, alongside settlement backlog and
projection-lag limits. In that same sustained run, record matching-outcome-to-
market-visible lag, book/depth/tape API latency and freshness, settlement
backlog, projection lag, connection pressure and database saturation. A fast
journal with stale market data fails cutover; a fast market API with an
ever-growing settlement queue also fails. Do not infer market-data performance
from the isolated journal write-shape proof.

## Recovery and fault gates

- Source replay or duplicate delivery: compare source identity and bytes with
  committed envelope coverage; never create a second financial transition.
- Crash before append: discard tentative state and re-evaluate from last
  committed head. Crash after append or lost reply: replay committed envelope
  and return its exact result; do not post a second DvP.
- Owner failover: only the holder of the journal-head fence may append. A stale
  owner cannot advance the head after takeover, even if it retains memory.
- Source/target independent restore: keep settlement offline until original
  source, ordered policy/funding inputs and journal through last acknowledged
  finality are recovered and independently verified. Use a non-reused
  incarnation; a database-local epoch cannot fence a rollback of itself.
- Corrupt/missing envelope, hash chain, source member, policy, opening,
  transition, four-leg conservation, or checkpoint reconstruction: fail closed
  and stop financial progress. Do not repair by inventing a new arbitration
  history in the same generation.

## Implementation and proof sequence

1. Write a versioned envelope contract and independent reference interpreter.
   Compare the current normalized path and journal candidate on identical
   source, policy, opening, hot/diverse/scarce, empty and break/retry cohorts.
2. Build the journal append, fenced head, snapshot/replay and one-owner
   evaluator behind a new default-off local proof command. Keep it independent
   of the old shadow worker; do not add a compatibility dual-write path.
3. Prove crash before/after commit, lost reply, duplicate, stale owner,
   source-only/target-only restore and changed policy/funding rejection.
4. Measure source read, preparation, in-memory evaluation, serialization,
   head lock, append/commit, WAL/TOAST/index bytes and projection catch-up on
   one fixed workload. Include book, depth and trade-tape response and
   matching-outcome-to-visible freshness in this **first integrated vertical
   proof**, not a later market-data campaign. Require the 640-trade hot chain
   <=145.45 ms, target <=121.21 ms for headroom, and source, journal, both
   projections and read APIs to keep up in a 300-second local 10k/s run with
   exact closed-cohort accounting. A component-only win or stopped-source
   drain is not a pass.
5. If the complete local proof fails materially, stop this PostgreSQL journal
   candidate. Decide a specialist ledger or a different settlement product
   contract from the measured failing component; do not tune single-digit
   percentages. If it passes, obtain independent architecture/code review,
   remove the old shadow ledger tables/workers and update D-050/D-059, APIs,
   restore and operator docs as breaking changes, then run CI/OCR and only
   afterward a matched disposable hosted qualification. Never test this on
   production.

Expected implementation location: `services/platform-runtime` for the ordered
owner and interpreter, `contracts/proto` for versioned envelope fields,
`scripts/dev/db/migrations/settlement` for the journal/head/snapshot schema,
and focused PostgreSQL integration tests beside the existing settlement tests.
Build/test: `cd services/platform-runtime && ./gradlew test --offline --console=plain`;
migration checks: `bun test scripts/dev/db/migrate.test.mjs`. Local proof uses
the dedicated settlement PostgreSQL target and disposable databases.

## Decided versus still open

Decided: journal commit, not materialized per-trade SQL rows, is the proposed
financial finality point; logically complete audit facts remain reconstructable
from immutable inputs and ordered results. Matching outcome remains trade
authority. Settlement and market-data reads may be asynchronous but must
expose their respective as-of frontiers. The first candidate is a default-off
PostgreSQL vertical proof with no compatibility dual-write. This decision is
recorded in D-060; it does not qualify capacity or authorize public cutover.

Still open before qualification: exact public status/enum names and read
freshness behavior, numeric book/depth/tape p95/p99 and source-to-visible age
limits, bounded batch limits, and the durable protocol for ordered
policy/opening/funding changes and external restore incarnation. Pick and
freeze these against real product requirements before claiming a pass. Do not
silently treat the current 250 ms market poll cadence, a check-only pre-trade
balance, or the isolated 28–37 ms append samples as proof of those contracts.
