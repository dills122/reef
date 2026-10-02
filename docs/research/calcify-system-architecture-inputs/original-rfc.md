# Reef Calcify — Proposed Full-System Architecture

**Status:** proposed RFC, not an accepted architecture decision and not a throughput qualification.

**Prepared:** October 2, 2026. Repository reference: `dills122/reef`, commit `0f4617a8b251a6fed5648b60c38e1c34837bc922`.

**Scope:** everything after the matcher durably records its outcomes: execution capture, trade processing, account bookkeeping, clearing/netting, settlement, operational reads, public market data, record keeping, replay, and recovery. Matching algorithms and order-ingress throughput are not redesigned here. Their source contracts and resource-control interfaces remain dependencies.

**Targets supplied by the project owner:** 10,000 trades/s for 600 seconds and 7,500 trades/s for 900 seconds, with replayability. These rates do not mean 10,000 arbitrary internal messages, source batches, API acknowledgments, or delayed-settlement obligations discharged per second.

## 1. Recommendation

Keep Phase 1/2 as the source-capture and context-resolution foundation. Extend it to cover non-trade lifecycle facts and causal completeness. Build a modular, deterministic post-trade runtime over durably ordered financial-domain inputs. Let that runtime emit complete authoritative decisions, including journal postings and settlement dispositions. Materialize operational PostgreSQL views independently. Archive the original sources, ordered decisions, and control inputs with explicit retention guarantees.

The initial physical shape is deliberately smaller than the business-stage diagram:

1. Source gateway: existing extraction/verification/resolution plus lifecycle capture and coverage.
2. Admission: routes complete, causally ordered input to closed financial domains.
3. Post-trade runtime: workflow, clearing/netting, scheduling, accounting, settlement, and exception modules running under one domain owner initially.
4. Read side: independent materializers, existing API adapters, and streaming delivery.
5. Operations: archive/reconciliation workers and external-integration dispatchers.

These are roles, not five mandatory new repositories or five always-separate services. Reuse the Kotlin runtime packaging and existing public/admin API boundaries. Separate processes where resource contention, ownership, or recovery requires it.

**Proposed technology baseline:** Kotlin domain modules; Kafka Streams Processor API and managed RocksDB; Redpanda as the Kafka-compatible durable log; Protobuf at internal boundaries; PostgreSQL for operational relational views and policy authoring; an S3-compatible store for archival history; the existing Go simulator for real-path load and fault tests.

**Explicit architecture amendment:** the recommendation makes committed post-trade result records the logical authority for Calcify decisions, with PostgreSQL holding a faithful relational materialization. Existing D-009 and the technical design emphasize relational/canonical PostgreSQL. Amend those decisions explicitly before adopting this boundary. A log containing account-ledger entries is still a conventional account ledger; it is not a blockchain. Do not accidentally operate two competing financial authorities. [R1, R2, R3]

## 2. Product semantics to preserve

The repository already describes an institutional post-trade lifecycle and two policy profiles. Preserve them rather than inventing a fast business model unrelated to the intended product. [R3, R4]

| Profile | Meaning | What must not change for speed |
|---|---|---|
| `instant-post-trade` | Automated actors and accelerated timing; initially gross settlement, with later versioned micro-batch netting | Allocation, affirmation, clearing/novation, instructions, attempts, ledger proof, and failures remain inspectable |
| `ops-realistic` | Configured calendars, deadlines, clearing, netting, deferred settlement, fails, and repairs | Executed trades and pending obligations must not be mislabeled as financially settled |

Use one engine with versioned policy, not independent implementations. Automated steps may execute together and appear as several semantic events inside one committed result. Waiting for a person, cutoff, external reply, or funding is a durable state, not an occupied thread.

Current constrained and unconstrained simulation behaviors must become explicit profile settings. In particular, absence of opening resources must not accidentally turn a realistic run into an unlimited-funding run. A legacy-compatibility mode may preserve that behavior while migration is tested, but it must name it.

This RFC is production-shaped simulation architecture. It does not establish legal settlement finality, regulatory compliance, production custody, or qualification as a real-money exchange.

## 3. Authority and completion vocabulary

| Fact or operation | Proposed authority | Meaning |
|---|---|---|
| Matching outcome | Existing committed venue-event log | What the matcher executed, accepted, rejected, amended, or canceled |
| Resolved context | Phase 2 output derived from immutable venue facts | Complete source context, not financial finality |
| Admission order | Registered post-trade input log | Which admissible action was considered before another within a financial domain |
| Post-trade decision | Committed `PostTradeCommitV1` result | What the post-trade state machine decided under recorded rules |
| Ledger journal | Journal postings contained in committed results | Authoritative accounting movements; relational ledger rows mirror them |
| Current decision state | Managed state obtained by applying the authoritative history | Efficient working state, not an independent editable authority |
| Policy/calendar/reference version | Immutable published control version and its activation position | Which exact facts governed a decision |
| Query row or cache | A projection with a progress token | What a particular read model has materialized |
| Archived history | Verified historical copy with provenance and manifests | Continued availability of the same authority after broker retention |

Avoid one overloaded `COMPLETED` flag. Track at least execution capture, processing-to-current-policy-boundary, settlement state, exception state, and projection visibility separately.

A broker consumer offset may mean input is staged in managed state. It does not necessarily mean that all related work has completed. Maintain separate staged, completed, and archived frontiers.

## 4. Core invariants

The following are acceptance criteria, not implementation suggestions:

- A settlement failure never deletes or rewrites an execution.
- Every authoritative accepted input has exactly one recorded disposition for its business action identity, or remains explicitly pending behind a tracked dependency.
- Identical business retries do not repeat financial effects. Conflicting payloads under one business identity are rejected or quarantined.
- Cash and securities are separate assets with separate exact units. They are never numerically balanced against each other.
- Every journal transaction balances within each relevant asset/book. Funding, issuance, withdrawal, fees, and corporate actions use explicit counterparties or control accounts.
- A local DvP settlement commits the linked cash and securities effects together or neither; a partial settlement does so for the settled portion only.
- Unsettled obligations, reservations, settled balances, and valuation are distinct concepts.
- No financial decision reads an asynchronously updated query balance as its authoritative available balance.
- Scarce-resource decisions have one durable order within their financial domain.
- Dependent source operations retain their causal order across the resolver/lifecycle split.
- Rules, calendars, reference data, external replies, clock advances, and operator commands that affect outcomes are reproducible inputs.
- Corrections append new facts and balanced compensating transactions; historical facts remain addressable.
- Healthy work is not discarded because another entity failed a business check. Integrity failures stop the smallest domain whose correctness cannot still be established.
- Missing required history, incompatible state, and unproven bootstrap fail closed.
- Replay does not contact production external systems or emit production settlement instructions.
- A projection cannot advance its checkpoint past effects absent from its own committed database state.
- Archive and state-retirement promises are explicit and tested, not inferred from the existence of a changelog.

## 5. End-to-end flow

```text
Go matcher — outside Calcify
    |
    v
Committed VenueEventBatch
    |
    +--> validated lifecycle / public execution facts
    |       +--> public tape and participant order/fill projections
    |       +--> private source-lifecycle inputs to financial admission
    |
    +--> Phase 1 commitments -> structural verification
            |
            v
        Phase 2 full match context
            |
            v
    Source coverage and causal-order gate
            |
            v
    Durable post-trade input, routed by financial domain
            ^
            |  published policies, account funding, operator commands,
            |  clock/cutoff commands, and recorded external responses
            |
            v
    Modular post-trade runtime
       trade capture / allocation / confirmation / affirmation
       clearing / novation / netting
       obligations / reservations / accounting
       instruction / settlement / repair / exception handling
            |
            v
    Committed post-trade results + managed-state changes
            |
            +--> PostgreSQL materializers -> Trade / Ledger / Ops APIs
            +--> integration dispatcher -> external or simulated adapters
            |                               |
            |                               +--> recorded response input
            +--> archive and reconciliation
            +--> derived participant notifications / analytics
```

Public tape and ordinary order status do not wait for settlement. Conversely, a low-latency tape event cannot prove that the financial system has processed or settled that execution.

## 6. Extending Phase 1/2 without discarding them

### 6.1 Retain the useful boundary

Keep immutable venue facts, source-generation registration, compact commitments, independent receipt diagnostics, managed state, complete resolved trade context, and explicit source consistency checks. Receipt existence remains a diagnostic fact; it is never a dependency for financial processing or a settlement acknowledgment.

Treat structural verification as structural verification. Context-dependent financial eligibility belongs after context resolution. A structurally bad source fact and a financially unsuccessful settlement are different failure classes.

### 6.2 Add non-trade lifecycle capture

Accepted resting orders, amendments, cancellations, expirations, rejects, and terminal order transitions must be available independently of whether a trade commitment exists. Reuse the source decoder and validation library, and where appropriate extend the extractor role to emit lifecycle batches transactionally with its checkpoint.

Do not have every projection repeatedly parse the entire venue JSON or query the matcher. Financial input should include immutable acceptance identity and any in-force revision/lifecycle evidence that the decision requires. Original acceptance economics must not be silently overwritten with the latest amended order.

### 6.3 Add causal completeness

A lifecycle stream can outrun the full-fact resolver. A cancellation observed at source position C must not release resources needed by an earlier unresolved fill F merely because C arrived first on a different downstream topic.

Recommended mechanism: source-lane coverage records and a bounded admission gate. For each source slice, know its source coordinates, complete trade membership/count, verification dispositions, and non-trade lifecycle events. Release financial inputs in source causal order only after the required resolved contexts or explicit terminal dispositions are present. Zero-trade slices can close immediately.

This may be implemented as an extension of the resolver/source-gateway topology, rather than a new service. State and output checkpoints must use the same transactional discipline. Bound retained pending slices by bytes and count; backpressure the producer side when the bound is reached. A maximum observed timestamp or empty consumer poll is not a completion proof.

A financial-domain stream can merge independent source lanes in a recorded order, but cannot violate an order's source dependencies. Public projections need not wait at this financial gate.

### 6.4 Repair identity and bootstrap before expansion

The previous review identified matcher/resolver run-scope disagreement and insufficient bootstrap/history continuity. Revalidate these against the candidate build and fix the contracts, not just lookup strings. An acceptance key needs a proven scope and lifetime; an order ID reused after terminal retirement may also require an acceptance/revision identity.

Register the complete application namespace: source/input/output topic UUIDs, source generation, partition routing epoch, application/state version, retained starting positions, and migration metadata. Missing offsets are not permission to start from an arbitrary retained beginning. Separate new generation, valid restore, and intentionally partial subscription modes.

## 7. Partitioning and deterministic admission

### 7.1 Three distinct key spaces

| Work | Ownership key | Rationale |
|---|---|---|
| Source capture and resolution | Source generation + partition; book identity includes run/session/instrument | Preserve matcher source order and source-local lookup |
| Financial decision | Closed financial-domain ID | Own every account, reservation, and obligation participating in an atomic invariant |
| Queries and analytics | Appropriate run/account/instrument/time partitions | Optimize reads without becoming financial owners |

For initially isolated simulations, one financial domain per run is the simplest proposed implementation. Confirm that no spendable account, CCP pool, or obligation is shared across those run domains. Otherwise define a wider ownership boundary. Do not split financial state by instrument merely because matching is split that way.

One hot run sharing cash across all instruments may remain one serial financial owner. That limitation must be exposed in the first capacity proof. More partitions with no additional independent work do not accelerate that domain.

### 7.2 Admission is a durable decision

Publish canonical `PostTradeInputV1` records to the financial-domain partition in an explicitly retained user topic. Do not treat a transient Streams internal repartition topic as the permanent admission journal: its lifecycle is managed for processing, not for the promised audit window. Preserve source causal order before publication. Multiple independent source lanes and admin inputs acquire a committed order in this log. The domain owner records a monotonically increasing domain sequence on each resulting decision.

Policy updates and clock advances must be ordered inputs, not mutable side reads. A version must be available and verified before actions referencing it become admissible.

For recovery, replay this exact admission order. For deterministic scenario recreation from a seed alone, add a separate deterministic admission mode with stable logical ticks, explicit participating-lane frontiers, and deterministic tie-breaking. This may introduce waiting; it is not the same guarantee as replaying recorded arrival order.

### 7.3 No naïve account sharding

Do not debit the buyer on one unrelated worker and credit the seller on another and call the workflow atomic. Initial cross-domain financial transfers should be explicitly unsupported, or use a specified escrow/prefunding/coordination protocol. Ordinary compensating sagas do not provide instantaneous DvP finality.

Parallelize decoding, immutable enrichment, and independent domains first. Change the financial ownership model only after a measured single-domain limit and a separately tested protocol exist.

## 8. Modular runtime and transaction contract

Start with one `PostTradeKernel` adapter inside a Kafka Streams task for each owned financial partition. Keep pure domain modules distinct even when their changes share one transaction:

```text
trade-processing/
workflow/
clearing/
netting/
obligations/
accounting/
settlement/
exceptions/
policy-and-clock/
```

A conceptual interface is:

```text
decide(currentState, orderedInput, immutablePolicy) -> Decision
Decision = events + stateChanges + journalTransactions + effectIntents
```

The function does not call PostgreSQL, an external adapter, a live price provider, or the wall clock. Large bounded tasks use explicit managed continuations rather than replaying the full historical prefix.

### 8.1 Proposed result envelope

```text
PostTradeCommitV1
    executionNamespace
    financialDomainId
    domainSequence
    decisionId
    actionId
    inputIdentity / inputPosition
    logicalTime
    policyVersion / calendarVersion / referenceVersion
    sourceReferences[]
    lifecycleEvents[]
    journalTransactions[]
    changedEntityVersions[]
    externalEffectIntents[]
    disposition
    semanticDigestVersion / semanticDigest
```

One decision can contain several lifecycle events. Each event and posting still has a stable identity and causation reference. Semantic events need not become independent network requests, transactions, or database round trips.

The Streams transaction coordinates managed-state updates, the result record, and consumed input offsets. A committed result is the decision acknowledgment; a successful call to `forward()` is not. Kafka Streams provides its transactional boundary for Kafka input/state/output, not for unrelated external systems. [T1, T2]

Keep each individual atomic decision within an explicit record-size limit. One broker transaction may batch many independent decision envelopes. A large netting cycle must use sealed manifests and bounded chunks; never increase the message limit indefinitely to fit millions of gross trades.

### 8.2 Who may write what

Only the financial owner can change authoritative account state or produce journal transactions. Workflow modules express settlement intent through the kernel; they do not mutate a second balance representation. Materializers cannot approve settlement. Operators issue audited domain commands rather than editing financial tables.

A trade's settlement status is derived from its authoritative obligation/discharge results. A workflow summary does not independently declare that a ledger posting occurred.

## 9. Full business lifecycle

| Module | Required facts | Decision rules and important failure behavior |
|---|---|---|
| Capture | Execution recorded; immutable trade identity; source provenance | Preserve the executed fact even when later processing fails |
| Trade processing | Allocations, booking/enrichment version, fees, account assignments | Allocation quantities and economics reconcile to source; validate references under a pinned version |
| Confirmation/affirmation | Confirmation version; actor affirmation; mismatch/timeout | Explicit waits and recorded outcomes; no implicit success when a deadline expires |
| Clearing | Submission, acceptance/rejection, novation and counterparty relationships | Clearing rejection does not undo matching; preserve the affected obligation and exception |
| Netting | Eligible gross contributions, closed net set, resultant obligations, membership | Versioned grouping, immutable gross lineage, deterministic closure and allocation of discharge |
| Obligation/accounting | Due amounts, reservations, unpaid amounts, journal transactions | No double spending or double counting of settled and pending resources |
| Settlement | Instruction, attempt, linked-leg result, partial/final completion or fail | Commit local linked effects atomically; distinguish unknown external status from rejection |
| Operations | Exception, ownership, repair, override, reversal, closure | Required role and reason; compensation rather than historical mutation |

### 9.1 Independent state dimensions

Represent trade and obligation states separately. A trade may be executed, affirmed, cleared, and partially settled while also having an open operational exception. One ever-expanding status enum is a poor substitute for these dimensions.

Useful trade dimensions: capture, allocation, affirmation, clearing, obligation membership, settlement summary, and exception summary. Useful obligation dimensions: active principal, due date, reserved amount, settled amount, remaining amount, current attempt, and failure/repair state.

### 9.2 Netting is not another name for batching

Transport batching changes when writes occur. Netting changes the obligations that need settlement. Keep those separate.

A proposed netting key includes financial domain, member/settlement account, CCP or bilateral counterparty, instrument, currency, settlement date, settlement location, segregation class, and netting-policy version as applicable. Do not net across incompatible currencies, custody arrangements, client pools, or agreements.

Record an explicit close event with the admitted cutoff and source-coverage proof. Gross contribution membership remains immutable. At activation, gross obligations replaced by netting become ineligible for independent gross settlement atomically with activation of the replacement net obligations. Late trades enter a later set or a versioned adjustment, rather than silently changing a sealed set.

A zero-net result still records how gross obligations were discharged by netting. Partial settlement of a net obligation requires a deterministic, recorded rule allocating discharge to its gross constituents; it must not mark every member trade fully settled.

DTCC's CNS illustrates the functional separation: eligible trades are netted to member/security positions, reducing deliveries while maintaining post-trade records. This RFC uses that separation, not a claim to implement all CNS or CCP risk functionality. [D1]

### 9.3 Clock and deadline handling

Publish `ClockAdvanced`, `CutoffReached`, and `NettingWindowClosed` as recorded controls. Index pending work by deadline and stable identity; process due work incrementally. For a logical tick, specify the ordering of funding, deadline processing, netting closure, and settlement attempts.

An empty poll or a wall-clock timeout cannot prove that all eligible trades for a tick have arrived. Close using registered input frontiers or an explicit admission cutoff. Persist continuation state for large due-work sweeps; do not scan every historical trade on each tick.

Seeded fault decisions should be derived from stable inputs such as scenario seed, entity/action ID, attempt number, and policy version, or logged as adapter responses. Thread scheduling must not advance a shared random generator in an economically meaningful way.

## 10. Ledger and resource semantics

### 10.1 Separate balances, obligations, and holds

Keep settled cash, settled securities, unsettled receivables/deliverables, active reservations, credit/short limits, and valuation distinct. Buying power is a versioned policy over appropriate components. Displaying pending economic exposure must not count it again as settled inventory or freely spendable cash.

Use checked fixed-point arithmetic with explicit asset units and scales. Multiplying price nanos by quantity can overflow even if both inputs fit a signed 64-bit integer; define bounded integers or checked wider/BigInteger arithmetic. Round according to a recorded policy, not floating-point behavior or locale.

### 10.2 Minimal local DvP example

For ten shares bought for 1,000 currency units, the illustrative settled movement is:

```text
Buyer cash:      -1,000
Seller cash:     +1,000
Seller security:   -10
Buyer security:    +10
```

Both asset totals conserve independently. These signed movements are not a universal debit/credit naming convention; account normal balances determine accounting presentation.

In `ops-realistic`, recording the trade initially creates the appropriate receivable/deliverable obligations, without immediately applying those settled movements. In `instant-post-trade`, automatic workflow transitions can reach a successful settlement in the same decision or tick when all requirements pass.

Four movements describe only the minimal gross example. Fees, split allocations, control accounts, clearing structures, and partial settlement can create more postings. Benchmark the actual configured profile.

### 10.3 Failed and partial settlement

A failed linked-leg evaluation creates a typed attempt result and remaining obligation, with no one-sided settled transfer. Partial settlement commits both legs for the permitted portion and preserves the residual obligation. Define minimum lots, price/fee rounding, retry priority, and the policy for resource contention explicitly.

Funding and repairs are ordered commands. A funding event does not retrospectively change the outcome of an earlier failed attempt; it permits a new recorded attempt.

### 10.4 Corrections and operator actions

A trade correction, cancel/bust, or accounting repair is a new authorized fact referencing the original. Reversing a completed settlement uses a complete balanced compensation transaction appropriate to the correction. An operator cannot simply flip `SETTLED`, edit a cash row, or delete one historical leg.

The existing legacy single-entry reverse operation deserves a migration review: do not preserve an unbalanced adjustment merely because its API name already exists. Route compatibility endpoints into a balanced action and record any deliberate semantic change.

### 10.5 Pre-trade dependency stays outside scope but must be explicit

Calcify can prevent double settlement and enforce post-match resources. It cannot retroactively guarantee that an unfunded order never matched. Preserve bounded ingress checks, and define any authoritative reservation/delegated-risk interface separately. Asynchronous projected balances cannot promise a strict pre-trade credit limit across lanes.

On post-match overload, stop or throttle new admissions through a bounded operational health signal. Already matched trades remain obligations to process; they are not discarded when the health gate closes.

## 11. External integrations and irreversible side effects

Internal simulated settlement can use one atomic domain transaction because Reef owns all relevant simulated balances. External bank, CCP, or custodian finality requires that external system's contract and evidence; a Kafka commit proves only Reef's local decision.

Commit the instruction/effect intent with the local state that requires it. Dispatch only from committed records. The dispatcher uses stable instruction/attempt identities and records responses as new durable inputs. Separate instruction accepted, transfer pending, rejected, status unknown, and externally final states.

Timeout is not proof of failure. Query status or reconcile ambiguous outcomes before issuing a new effect; use provider idempotency where available. A retry without a safe external idempotency or status protocol must not be described as exactly once.

Replay disables effect dispatch by default. Simulator adapters use the same command boundaries and supply recorded or reproducible responses. [R3, R4]

### 11.1 Security and operational authority

Use separate identities and least-privilege topic/database permissions for source readers, financial owners, materializers, archive writers, and integration dispatchers. Encrypt service connections and protect archive credentials independently. Only authorized domain writers may produce financial-result records; a checksum does not authenticate an untrusted producer.

Validate actor, participant/account scope, command permission, and expected entity version at the command boundary. Record the authorization/policy version needed to explain the original decision during replay; do not replay history against today's mutable roles. The kernel still validates domain invariants even for privileged callers. Public tape, private participant trade details, and operator/compliance records need separate response contracts and access checks.

A namespace manifest must also identify the approved writer application and routing epoch. Kafka fencing inside one application does not prevent a different application namespace from producing competing financial histories; permissions and operational ownership must exclude that configuration. Break-glass actions require an auditable command and reason, not direct ledger-table access.

## 12. Projection and materialization design

### 12.1 Independent read families

| Family | Input | Typical relational views |
|---|---|---|
| Public market | Validated venue execution/lifecycle facts | Tape, bars, published market snapshots |
| Participant execution | Validated source facts | Own orders, fills, amendments, command outcomes |
| Trade operations | Post-trade committed facts plus captured trade identity | Trade summary, allocations, affirmation, clearing, current obligations, exceptions |
| Accounting | Committed journal and balance/obligation changes | Journal entries, current balances, reservations, due/settled exposures |
| Audit/reporting | All required authoritative histories | Lineage, reconciliation, run reports, historical exports |

Preserve existing `/api/v1` routes and Bot SDK behavior through adapters where possible. Expand `/api/v1/data/availability` rather than inventing a parallel inventory of freshness semantics. Public trade tape must not expose full resolved contexts or counterparty identities. [R5]

### 12.2 Database transaction pattern

For each bounded batch, a materializer:

```text
reads committed records
locks/checks its SQL projection checkpoint and ownership epoch
validates next input range and semantic identities
inserts append-only journal/timeline rows idempotently
coalesces repeated updates to the same current-state row
upserts current state using authoritative entity versions
advances its SQL checkpoint in the same database transaction
commits SQL
then optionally commits the Kafka consumer position
```

On reassignment, resume from the SQL checkpoint, not an independently advanced broker offset. A late/stale worker must fail a checkpoint/epoch check rather than apply effects behind the new owner.

A newer entity after-image can replace an older current-state version. Repeated deltas must not be re-applied after a crash. Cross-partition ownership changes require routing/version transition rules, not lexicographic comparison of unrelated offsets.

Use multi-row writes or bounded staging/COPY-and-merge where measurements justify them. PostgreSQL documents COPY and deferred index building for bulk loading; that does not mean dropping live indexes or using unlogged tables for authoritative records. [T5]

### 12.3 Minimize write amplification

A domain decision may contain a dozen inspectable lifecycle facts, but it need not cause a dozen serial SQL commits. Append event/journal rows in batches and write one final current-state image per affected trade/account in the batch. Keep indexes tied to actual queries.

Do not replace one hot path with a chain of projectors querying each other's tables. Consumers should obtain the changes and immutable data they need from committed contracts, with only exceptional historical lookups.

Financial API responses that require consistent balances, postings, and settlement status should use one transactionally updated financial read bundle or an explicit common completed cut. Independent projections each passing a minimum cursor does not, by itself, create a cross-store snapshot.

### 12.4 API freshness and streaming

Return opaque progress tokens that identify source/application generation and relevant completed positions. Cross-source combined views may need a vector, not a single global offset. Include freshness state and effective business time separately from processing observation time.

Provide bounded wait-for-position semantics for read-after-action. If the required projection is behind, return a documented pending/unavailable response instead of pretending that a stale balance is current.

Use keyset pagination and bounded history ranges. Stream participant notifications from committed inputs with resumable positions and bounded client buffers. A slow WebSocket client is disconnected with a resume token; it cannot block financial processing. Notification delivery is not financial finality.

No normal trade, balance, or tape request should rebuild a run, scan the full ledger, or query matching-engine private state. Historical proof is a separate bounded/export operation.

## 13. History, archive, replay, and state lifetime

### 13.1 Three replay modes

| Mode | Inputs | External effects |
|---|---|---|
| Operational recovery | Compatible committed checkpoint/changelog and retained tail | Resume only through the normal deduplicated dispatcher protocol |
| Audit recomputation | Original ordered admissions, policies, reference versions, source facts, clock/funding/response inputs | Disabled; compare independently recomputed outcomes |
| Counterfactual simulation | Deliberately changed policies or inputs in a new execution namespace | Isolated simulation only |

A restored transport namespace must not create a new economic identity for an old execution. A counterfactual fork, in contrast, must deliberately have a different execution namespace. Source locator and execution/action identity are different fields.

### 13.2 Archive contents and trust

Archive original source bytes with original coordinates, canonical admissions, result envelopes, control versions, integration responses, and run manifests. Store segment bounds, record counts, semantic digest versions, raw-byte checksums, and routing/topic identities.

Use a separately protected archive destination and immutable/versioned manifests. Hashes detect accidental alteration only relative to trusted manifests; they do not by themselves defeat an attacker who can rewrite both data and manifests.

Parquet and analytical exports may be derived later. They must not be the only retained representation when they discard original payload or replay ordering.

### 13.3 Pruning and snapshots

No arbitrary TTL for old accepted orders, unresolved obligations, or financial deduplication. First implement sealed-run retirement: all relevant input cuts are known, all required work is complete or explicitly carried forward, archive is verified, and no active domain still references the state.

Account balances and outstanding obligations often outlive the trading session. They belong in durable carried-forward state, not in a deleted per-session cache. Removing run-local state is safe only for a truly closed run domain.

Use completed-prefix proofs and bounded recent duplicate evidence only where their contracts prove it safe. Exact deduplication over an unlimited business-ID history inherently requires retained exact history or an explicit namespace/lifetime bound; a Bloom filter is not sufficient financial authority.

Snapshot manifests bind state schema, application version, source/input generations, completed domain positions, policy versions, and archive coverage. Test a restore that discards local RocksDB and one that rebuilds without relying on the current changelog.

Protobuf serialization is not canonical across implementations/builds. Preserve raw-byte checksums for exact archived bytes and use a separate versioned semantic canonicalization for cross-version financial replay comparisons. [T6]

## 14. Physical technology decisions

| Technology | Proposed role | Guardrail |
|---|---|---|
| Kotlin | Pure financial/workflow modules and runtime integration | No per-trade remote calls inside the deterministic kernel |
| Kafka Streams | Ownership, Kafka transactions, managed state, restoration/standbys | Pin and test actual client/broker versions; do not treat EOS as external-system atomicity |
| Redpanda | Durable source/admission/result logs | Multi-node RF3 for qualification; tested quorum/acknowledgment/failure behavior, not RF1 fixture claims |
| RocksDB | Active trade/obligation/account state and due-work indexes | Bound total native/cache/memtable budgets across active and standby tasks |
| Protobuf | Versioned internal wire contracts | Explicit enums/units/identity; schema evolution and semantic hashing rules |
| PostgreSQL | Queryable account journal, current views, authoring/control metadata | No ordinary synchronous financial decision reads from projections |
| S3-compatible storage | Archive segments/manifests and optional validated checkpoints | Verified completeness before required broker history can expire |
| Existing observability stack | Metrics/traces, lag, resource and recovery telemetry | Full decision audit independent of trace sampling; avoid unbounded metric labels |

Kafka Streams task parallelism is bounded by input partitions, and native RocksDB resources must be budgeted across tasks and replicas. [T2, T3]

Redpanda's current documentation distinguishes ordinary write caching from transactions: transactional writes and consumer offsets are described as disk-flushed before acknowledgment even where ordinary user-topic write caching is enabled. Freeze and validate the deployed version's actual behavior; do not transpose Kafka ISR assumptions onto Redpanda quorum replication. [T4]

Do not introduce a second broker, custom consensus system, Redis financial authority, or a workflow-service deployment per semantic trade stage. Broader analytical stores can be added when PostgreSQL/reporting measurements justify them.

TigerBeetle is a credible conditional alternative for the authoritative account ledger: it supports linked transfers across separately identified ledgers, and immutable transfer history. It would require an explicit bridge between its durable ledger result and Reef's workflow state; it does not make two independent systems one transaction. Evaluate it only if the early financial-domain spike falsifies the recommended runtime or its operational guarantees. [T7]

A compact PostgreSQL authoritative journal is another legitimate fallback. In that case, account changes, journal rows, action deduplication, workflow state, and outbox must commit in one PostgreSQL transaction. Do not silently mix that model with the Kafka-authoritative model.

## 15. Capacity and service-level contracts

### 15.1 Rate definitions

| Metric | Definition |
|---|---|
| `captured_trades/s` | Distinct source executions represented in committed capture output |
| `resolved_trades/s` | Distinct complete contexts independently observed as committed |
| `post_trade_processed/s` | Distinct executions advanced to their current policy-required boundary, with recorded obligations or exceptions |
| `settled_trades/s` | Distinct executions whose required financial obligations are discharged, only meaningful for the chosen profile |
| `projected/s` | Appropriate committed results applied to the required read bundle |
| `visible/s` | Results available through required API reads within the freshness contract |

For the no-fault instant profile, full qualification includes successful settlement and required projections, not only context resolution. For the realistic profile, waiting until a future settlement date is legitimate business state; overdue due-work or a growing processing backlog is not.

The requested tests contain 6,000,000 and 6,750,000 trades respectively. A minimal gross four-movement settlement would produce 24,000,000 and 27,000,000 journal movement rows. Real configurations may produce more. Two-new-order fixtures require approximately twice the successful submit-command rate, but actual command/trade ratios vary.

### 15.2 Provisional latency budgets

These are proposed initial planning values, not established Reef requirements or achieved measurements:

- durable venue execution to committed instant post-trade decision: p95 <= 500 ms;
- durable venue execution to required trade/account read visibility: p95 <= 1 second;
- p99 tails, initial capture freshness, read response times, and recovery RTO/RPO: freeze with the first baseline before qualification.

A stricter agreed target supersedes these proposals. Multiple sequential 100 ms commit windows consume latency budget before CPU work; measure transaction visibility as well as handler time. Kafka Streams documents its commit interval and transactional visibility behavior. [T2]

### 15.3 Workload and headroom

Qualify one hot financial domain, many domains, one hot instrument, spread instruments, hot accounts, old resting orders, amendments/cancels, many fills per incoming order, delayed workflows, and concurrent API reads. Do not conceal a one-run bottleneck behind many independent runs.

Measure source bytes and batches, contexts, semantic events, physical broker records, actual changelog bytes, journal rows, database WAL, coalesced row updates, cache misses, native memory, and archive growth per trade.

Headroom must be selected from the recovery objective. With arrival rate lambda and recovery capacity mu, backlog B drains in B/(mu-lambda), only when mu exceeds lambda. An illustrative 20-second interruption at 10,000/s creates 200,000 trades; 12,500/s recovery capacity drains that backlog in 80 seconds while arrivals continue.

### 15.4 Failure qualification

Freeze commit SHA, policy, schema, routing, topology, hardware, producer pacing, replication, retention, acknowledgment settings, and observer configuration. Test active-owner death, stale-owner resumption, local-state deletion, broker loss, projection outage, replay, poisoned source, missing history, and unknown integration responses while load runs.

Count independent committed output and exact business identities. Compare full economic facts and financial conservation, not only matching record counts. Test minimum all-profile rates and drain behavior on unchanged candidates. A final drain is not a substitute for sustained in-load capacity.

## 16. Incremental implementation plan

Archive/reconciliation, observability, and compatibility work start with the first vertical slice; they are not entirely deferred to the end.

| Slice | Deliverable | Exit gate |
|---|---|---|
| 0 — Contract freeze | Authority ADRs, identity/lifetime, domain ownership, profiles, completion metrics, golden scenarios | Source and financial examples have unambiguous expected outcomes; no contradictory authority claims |
| 2A — Complete source boundary | Fix scoped identity/bootstrap; non-trade facts; causal coverage and bounded gate | Same IDs across runs, cancel-before-delayed-fill delivery, zero-trade slices, restart, and retention cases pass |
| 3 — Financial kernel spike | Pure modules, ordered input, minimal full instant lifecycle, local DvP, typed failure, committed result | Exact state/journal replay; scarce-account winner stable; measured hot-domain headroom before broad expansion |
| 4 — First usable end-to-end slice | Existing Trade/Ledger API adapters, batched SQL materialization, progress tokens, one repair command, archive segments | Real orders -> matching -> Calcify -> API; cash/security fail and repair; restart parity; shadow comparison |
| 5 — Resource/lifecycle completeness | Funding/withdrawals, holds policy, amendments/terminal release, revisions, compensation | Conservation, no double spend, no premature hold release, no unsafe direct table mutation |
| 6 — Realistic workflows | Allocation revisions, affirmation/mismatch/deadlines, clearing rejection/novation, recorded adapter responses | Scenario clock advances without wall-clock dependence; role checks and pending/exception visibility |
| 7 — Netting and richer settlement | Sealed sets, gross-to-net membership, activation, partial settlement, aged fails, retry priorities | No gross/net double discharge; deterministic partial discharge allocation; late-input and large-batch tests |
| 8 — Operations and durable lifetime | Broader operator queues, verified state retirement, independent audit replay, large-state restore | History-loss and restore matrices; repaired exceptions and archived proofs agree |
| 9 — Capacity and cutover | Both requested durations under full target profile, read load, recovery under load, single-writer migration | Exact reconciliation, bounded lag, stated latency targets, no duplicated legacy/Calcify effects |

Every added slice receives a small paced full-path test, a burst test, deterministic replay tests, and a capacity regression before its scope expands. Major load qualification occurs repeatedly, not just in slice 9.

### Slice 3 scope in concrete terms

Implement three initial ordered input types: `OpenFinancialDomain`, `CaptureResolvedTrade`, and `FundAccount`. Add `AttemptSettlement` when an explicit attempt input is needed rather than an automatic transition. Use a pinned instant profile, exact money units, stable action IDs, and complete journal transactions.

The spike must include all mandatory automatic semantic transitions as facts, even when the corresponding human/external workflows are not implemented yet. A named system actor supplies automated decisions. Start with gross settlement; do not call an unimplemented netting stage complete.

Prove constrained success, insufficient cash, insufficient securities, shared-account competition across instrument lanes, identical retry, conflicting retry, crash before/after commit, and audit recomputation. This is the earliest decisive test of the proposed financial authority; do it before a large operator UI or elaborate clearing workflow.

### Cutover discipline

Shadow Calcify from the same source without enabling duplicate external/financial side effects. Compare identities, profile choices, obligation states, journal effects, and balances with the legacy implementation or an independent expected-result oracle. Differences require adjudication: legacy behavior is not automatically correct.

Cut over one closed namespace/run at a sealed boundary where practical. Record the cutover epoch, starting state, remaining obligations, reservations, and deduplication lineage. One writer owns financial effects. Rollback is a coordinated boundary transition, not enabling both implementations or pointing old code at unrecognized new state.

## 17. Proposed decision register

These decisions should be approved or amended explicitly before agents implement them:

| Proposed ADR | Decision |
|---|---|
| CAL-ARCH-01 | Matching authority unchanged; Calcify result log becomes post-trade decision/journal authority |
| CAL-ARCH-02 | Initial financial ownership is a closed domain; isolated run is a supported domain mapping |
| CAL-ARCH-03 | Recorded admission order is authoritative for recovery; seed-only deterministic recreation is a separately specified mode |
| CAL-ARCH-04 | One domain model supports realistic and instant profiles; semantic transitions may share a physical commit |
| CAL-ARCH-05 | Coverage/dependency gates connect non-trade source lifecycle with delayed trade resolution |
| CAL-ARCH-06 | SQL materializers own transactionally committed checkpoints and versioned query bundles |
| CAL-ARCH-07 | Stable execution/action identities survive transport restores; replay/fork namespaces are explicit |
| CAL-ARCH-08 | Archive scope, state retirement, retained replay window, latency budgets, and fault model are qualification inputs |

## 18. Initial acceptance scenarios

| Scenario | Required result |
|---|---|
| Simple crossed pair | One execution, expected lifecycle chain, one settled obligation, balanced journal |
| Old resting order fills later | Original immutable acceptance and applicable revisions resolved correctly |
| One aggressor, many fills | Every source trade ordinal accounted for; no source-batch-sized assumption about trade count |
| Same IDs in two runs on one source partition | Independent correct results; no false accepted-fact conflict |
| Cancel arrives before delayed earlier fill downstream | Resources released only after causal predecessors are accounted for |
| Two instruments spend one account | Durable winner order; no overspend caused by independent book lanes |
| Underfunded trade | Executed trade preserved; explicit failed/pending obligation; no one-sided settlement |
| Funding after failure | New funded attempt succeeds without rewriting the old failure |
| Same action retry / changed payload retry | Original result reused / conflict recorded without duplicate effects |
| Partial net settlement | Correct settled portion; remaining net obligation; deterministic gross attribution |
| Projection crash after SQL commit | Resume from SQL position; no repeated deltas or missing history |
| Kernel crash after broker commit | Restore exact state and outcomes; no duplicate decision effects |
| External timeout after acceptance | Status remains unknown pending reconciliation; no blind duplicate payment |
| Source/output recreation or required retention loss | Explicit recovery failure; no silent earliest reset |
| Local state/changelog unavailable | Declared archive-based recovery path either proves completeness or refuses |
| Run closure | Complete manifests and carry-forward state before retirement; replay still satisfies promised window |

## 19. Sources and interpretation

Repository sources were read through the connected GitHub tool at the pinned commit. They establish existing requirements and contracts; the architecture choices in this RFC are proposals. Primary external sources establish specific platform guarantees and post-trade concepts, not a proof of Reef throughput.

- **R1** — `docs/DECISIONS.md`, especially D-004 through D-011, D-037/D-038, D-041/D-042, and D-043/D-044. Repository: `https://github.com/dills122/reef/blob/0f4617a8b251a6fed5648b60c38e1c34837bc922/docs/DECISIONS.md`
- **R2** — `REEF_TECHNICAL_DESIGN.md`: existing modular platform, post-match domains, and canonical persistence direction. `https://github.com/dills122/reef/blob/0f4617a8b251a6fed5648b60c38e1c34837bc922/REEF_TECHNICAL_DESIGN.md`
- **R3** — `docs/POST_MATCH_STANDARDS.md`: roles, calendars, netting, journal corrections, simulation adapters. `https://github.com/dills122/reef/blob/0f4617a8b251a6fed5648b60c38e1c34837bc922/docs/POST_MATCH_STANDARDS.md`
- **R4** — `docs/SETTLEMENT_CLEARING_STRATEGY.md`: realistic/instant profiles, full lifecycle, existing gross finality and repair behavior. `https://github.com/dills122/reef/blob/0f4617a8b251a6fed5648b60c38e1c34837bc922/docs/SETTLEMENT_CLEARING_STRATEGY.md`
- **R5** — `docs/TRADING_MARKET_DATA_BOUNDARIES.md`: existing API surface, visibility, holdings, source and financial boundaries. `https://github.com/dills122/reef/blob/0f4617a8b251a6fed5648b60c38e1c34837bc922/docs/TRADING_MARKET_DATA_BOUNDARIES.md`
- **T1** — Apache Kafka, Streams Core Concepts. `https://kafka.apache.org/43/streams/core-concepts/`
- **T2** — Apache Kafka, Streams Configuration and Architecture. `https://kafka.apache.org/43/streams/developer-guide/config-streams/` and `https://kafka.apache.org/43/streams/architecture/`
- **T3** — Apache Kafka, Streams Memory Management. `https://kafka.apache.org/43/streams/developer-guide/memory-mgmt/`
- **T4** — Redpanda, Manage Topics, write-caching behavior. `https://docs.redpanda.com/streaming/current/develop/manage-topics/config-topics/`
- **T5** — PostgreSQL, Populating a Database and COPY. `https://www.postgresql.org/docs/current/populate.html` and `https://www.postgresql.org/docs/current/sql-copy.html`
- **T6** — Protocol Buffers, Serialization Is Not Canonical. `https://protobuf.dev/programming-guides/serialization-not-canonical/`
- **T7** — TigerBeetle, Data Modeling, Currency Exchange, and Correcting Transfers. `https://docs.tigerbeetle.com/coding/data-modeling/`, `https://docs.tigerbeetle.com/coding/recipes/currency-exchange/`, `https://docs.tigerbeetle.com/coding/recipes/correcting-transfers/`
- **D1** — DTCC, Continuous Net Settlement. `https://www.dtcc.com/clearing-and-settlement-services/equities-clearing-services/cns`
- **D2** — BIS/CPMI-IOSCO, Principles for Financial Market Infrastructures, settlement finality and exchange-of-value settlement. `https://www.bis.org/committees/cpmi/pfmi/overview`
- **D3** — SEC staff, T+1 Settlement Cycle FAQ. `https://www.sec.gov/exams/educationhelpguidesfaqs/t1-faq`

**Bottom line:** preserve the full post-trade business model, but keep the physical hot path short. Prove the ownership, order, and commit boundary early. Scale projections and history independently, and make every later module consume or emit explicit authoritative facts rather than rediscovering the past through database scans.
