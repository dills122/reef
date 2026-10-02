# Calcify system architecture — research and reconciliation

Date: October 2, 2026. Status: proposed decision support; no new architecture
accepted and no runtime changes. Owner: Reef project owner.

Baseline: `16e15340919bc9330afbbfec0f9b089115f3e57f`, remote master after PR #461.
Question: which financial authority, ownership and runtime best preserve Reef's
deterministic simulation, replay and audit requirements while pursuing 10,000
trades/s for 600s and 7,500 trades/s for 900s?

## Recommendation

Prove a small Kotlin financial kernel in the existing managed Kafka Streams /
RocksDB / Redpanda stack first. Retain complete committed financial decisions;
derive SQL reads independently. This is a candidate, conditional on reconstruction,
business atomicity and one hot closed-domain capacity proofs. It changes the
physical authority described by D-009 / technical design; owner approval and an
explicit ADR amendment precede adoption.

Use transactional Postgres as a credible comparison for the same kernel, shared
accounts and workload. There is no evidence here that SQL inherently cannot meet
10k trades/s. Consider TigerBeetle if accounting assurance or measured bottlenecks
justify a separate ledger and its integration cost. Do not replace the runtime
with Flink, Aeron Cluster or Temporal without a specific failed requirement.

Full proposed design and bounded proofs: [RFC](../work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md).

## Method and evidence labels

- **Fact:** primary documentation or source at pinned baseline.
- **Observation:** retained experiment with its own historical code/config scope.
- **Inference:** architectural judgment based on evidence, not a platform promise.
- **Unknown:** requires an experiment or owner decision.

Read all [supplied inputs](calcify-system-architecture-inputs/README.md), current
Calcify contracts/processors/readers, accepted D-009/D-042/D-043 and simulation
semantics, throughput ledger, performance learnings and projection scaling plan.
Codebase Memory project `reef-calcify-architecture`, full generation
`2026-10-02T05:25:50Z`, covered ten cited contract/source paths with matching
metadata; focused source reads verified behavior. Graph reports partial parsing
elsewhere and remains best-effort evidence. No benchmark or runtime test executed
for this documentation change.

Research used primary maintainers/standards bodies. Stop condition: credible
alternatives compared on the same guarantees; remaining unknowns have finite proof
gates. Vendor throughput claims do not qualify Reef.

## 1. Reconcile the supplied reviews against current master

| Addendum / earlier claim | Current evidence | Disposition |
| --- | --- | --- |
| Resolver omits run identity | PR #461 adds authoritative outcome run, `TradeSourceV1.run_id`, framed `(generation,runId,orderId)` keys and both-side validation | Already corrected in code. Do not repeat this repair. |
| Contract identity conflict | `contracts/calcify/README.md` describes new keys; `contracts/proto/README.md` retained older generation-only wording | Correct that paragraph in this change; lifetime mismatch remains below. |
| Matcher terminal eviction permits reuse but Calcify refuses changed acceptance | Matcher run/order reservations can expire; resolver retains immutable acceptance and rejects conflicts | Real remaining contract gap. Recommend unique internal order ID for full run, enforced before matching for Calcify-enabled runs; clients can reuse a separate display reference. Explicit incarnation is alternative, not an implicit overwrite. |
| All-acceptances-first is unsafe | `CalcifyResolverProcessor.ingest` merges every acceptance before trade resolution | Safe only under full-run no-reuse contract. If incarnation reuse is introduced, require exact incarnation in fills or sequential temporal resolution. |
| Generic fault commit could preserve partial financial mutation | Existing resolver catches integrity faults after some index writes and requests commit | Do not copy this into financial adapter. Source resolver has different semantics; financial decision must validate before writes and abort on unexpected failure. |
| Working journal proves complete recovery | Reservations, due work, policy activation, attempts, dedup and staged inputs also govern future decisions | Accept correction: `decide` plus `evolve`, complete state coverage and certified cuts. |
| Financial domain equals account / instrument lane | Atomic shared-account operations connect resources transitively | Accept correction: isolated run domain first; hot-domain proof required. Broker multi-partition atomic writes do not solve concurrent resource reads. |
| Write caching weakens every transaction acknowledgement | Redpanda documents transactional writes and consumer offsets as excluded from ordinary caching | Withdraw blanket claim for those paths. Keep pinned config and failure tests; do not rewrite historical attempts. |
| UUID checks establish all disaster-recovery safety | Topic deletion and remote recovery have different platform guarantees | Accept correction: ordinary failure vs coordinated restoration vs missing history; no automatic topic recreation. |
| Archive must precede first useful slice | Broker history can cover explicitly bounded run/replay/outage contract | Archive service optional for that contract. History coverage, including byte retention, mandatory. |
| Terminal source disposition discharges trade | Structural processing / verification says nothing about financial obligation | Accept correction: executed trade stays captured with pending/exception state. |
| Full seed determinism follows from deterministic reducer | Strategy observations, source outcomes, ticks, external responses and continuation ordering also matter | Accept correction: separate recovery, same-admission recompute and seed-only simulation promises. |
| Capacity has effectively passed | Historical paired source test reconciled exactly but missed producer duration; later profiles unrun | Not qualified. New financial path has no capacity result. |

Other retained source limitations: `BrokerVenueSourceReader` makes synchronous
broker/metadata calls inside the resolver's drain; 20ms outer budget does not bound
each nested call. `ResolverConsumerGate.seekTargets` starts at current beginning
when no checkpoint exists; declared genesis/restore/partial-mode policy is still
needed. Current accepted-order rows and per-commitment done keys grow. Source,
verified and output identities are bound in newer runtime, but that is not an
atomic administrator fence. Measure before adding prefetch; do not replace the
source reader solely because it is custom.

Independent review additionally identified ambiguous matcher execution/trade ID
concatenation in `Service.appendMatch` (`service.go:1060–1062` at baseline). Equal
ordinal with order pairs `("a-b","c")` and `("a","b-c")` can collide. RFC P0 now
explicitly tests collision-safe authoritative identity, repeated fills and restore;
this documentation does not fix the source implementation.

## 2. Primary evidence and consequences

### Managed Kafka state

**Fact:** Kafka Streams EOS coordinates input offsets, changelog state and Kafka
outputs. It does not encompass arbitrary database or external settlement effects.
[Kafka Streams concepts](https://kafka.apache.org/43/streams/core-concepts/).

**Fact:** tasks and partitions bound parallel work; several tasks can share one
thread. Standbys improve state availability, not application resource invariants.
[Streams architecture](https://kafka.apache.org/43/streams/architecture/).

**Fact:** caches can coalesce managed writes; native RocksDB memory needs budgeting
alongside heap and task multiplicity. Wall-clock punctuation is scheduling, not a
business ordering protocol. [Memory management](https://kafka.apache.org/43/streams/developer-guide/memory-mgmt/),
[Processor API](https://kafka.apache.org/43/streams/developer-guide/processor-api/).

**Inference:** use managed state rather than build a new checkpoint/changelog
framework. Validate/pre-encode the complete decision before mutation; let unexpected
adapter failures abort the transaction. Separate domain serialization from broker
partition/thread/process boundaries. Pending continuations must be managed state.

### Broker failure model

**Fact:** Redpanda excludes transactions and consumer offsets from ordinary write
caching. Official Cloud transaction docs describe removing deleted partitions from
in-flight transactions; legacy Streaming23.3 docs additionally warn that remote
recovery does not guarantee transaction atomicity.
[Write caching](https://docs.redpanda.com/streaming/current/develop/manage-topics/config-topics/#configure-write-caching),
[Cloud transactions](https://docs.redpanda.com/cloud-data-platform/develop/transactions/),
[Streaming23.3 transactions](https://docs.redpanda.com/streaming/23.3/develop/transactions/).

**Source limit:** current Streaming URL returned conflicting evidence between
reviewer and author retrievals. Author's October2 `open`/`find` view labelledv26.2
included deletion/remote-recovery caveats at lines105/112; reviewer retrieval omitted
them. Explicit26.2 URL failed retrieval. Neither Cloud/legacy wording nor this
variable current-page view establishes tested26.2.3 behavior; qualify version and
retain pinned-version proof rather than claiming blanket current-version guarantee.

**Inference:** topic UUID monitoring alone cannot prevent administrative deletion
during a commit. Protect active histories with permissions/change control; certify
coordinated restore cuts, or refuse continuation. Use version-pinned RF3 durability
settings and independent read-committed observers. Existing probes used Redpanda
26.2.3; do not infer guarantees from an unpinned future version.

### Transactional Postgres alternative

**Fact:** serializable transactions provide a serial equivalent with whole-
transaction retries on serialization failures. Explicit row locks can serialize
contending account updates. Transactional outbox keeps event intent in the same
database transaction; delivery still requires idempotence.
[Isolation](https://www.postgresql.org/docs/current/transaction-iso.html),
[Row locks](https://www.postgresql.org/docs/18/explicit-locking.html),
[Debezium outbox](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html).

**Inference:** a DB-authoritative option commits accounts, reservations, journal,
workflow, dedup, domain sequence and outbox together. Serialize against declared
admission order, not whichever concurrent transaction wins. It may reduce authority
and SQL visibility complexity, at cost of WAL, locks, retries and hot-domain writes.
Compare bounded batches and exact same semantics before rejecting it.

### Specialized ledger alternative

**Fact:** TigerBeetle linked chains commit or fail as a unit, and pending transfers
reserve resources before post/void. Each transfer stays within its asset ledger;
linked transfers can compose several asset movements. Arbitrary application metadata
lives elsewhere. [Linked events](https://docs.tigerbeetle.com/coding/linked-events/),
[Data modeling](https://docs.tigerbeetle.com/coding/data-modeling/),
[Two-phase transfers](https://docs.tigerbeetle.com/coding/two-phase-transfers/),
[System architecture](https://docs.tigerbeetle.com/coding/system-architecture/).

**Inference:** useful ledger assurance, but it does not atomically commit arbitrary
Kafka workflow state with account postings. That bridge needs stable transfer IDs,
unknown-result reconciliation and one ledger authority. Its optional pending timeout
is not automatically Reef logical-time expiration. New cluster/client/schema and
bridge costs make it a conditional candidate, not default first addition.

### Other runtimes

| Option | Documented mechanism | Reef implication (inference) |
| --- | --- | --- |
| Aeron Cluster | Replicated deterministic state machine; ordered inputs, disciplined time and snapshots | Credible latency-oriented alternative if managed Kafka misses measured objective. Adds consensus/archive/transport integration and custom adapters; no financial model supplied. |
| Flink | Coordinated checkpoints, replay and state recovery | Useful for complex stream computation. Does not decide account ownership or make arbitrary sinks transactional; another runtime is hard to justify for first serialized domain. |
| Temporal | Durable workflow orchestration | Potential external/long-wait orchestrator later. No reason yet to create a workflow/history per 10k/s trade; keep initial waits in indexed financial state. |

Sources: [Aeron replicated state machines](https://aeron.io/docs/cluster-quickstart/replicated-state-machines/),
[Flink fault tolerance](https://nightlies.apache.org/flink/flink-docs-stable/docs/learn-flink/fault_tolerance/),
[Temporal workflows](https://docs.temporal.io/workflows).

### Post-trade semantics, records and encoding

**Fact:** FIX separates allocation, confirmation, trade capture and settlement
instructions; DTCC CNS nets eligible obligations rather than erasing gross trades.
PFMI exchange-of-value principle links finality of both obligations.
[FIX post-trade specification](https://www.fixtrading.org/online-specification/),
[DTCC CNS](https://www.dtcc.com/products-and-services/clearing-settlement-services/equities-clearing/cns),
[CPMI-IOSCO PFMI](https://www.bis.org/committees/cpmi/pfmi/overview).

**Inference:** retain semantic events inside one decision/runtime initially. Gross
execution, net obligation, attempt, financial settlement and exception closure need
distinct identities/statuses. Local simulated DvP is a model property; external
finality requires a separate protocol and legal/product contract.

**Fact:** Protobuf deterministic serialization is not canonical across implementations
or schema evolution. S3 Object Lock supplies versioned retention controls.
[Protobuf serialization](https://protobuf.dev/programming-guides/serialization-not-canonical/),
[S3 Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html).

**Inference:** hash normalized domain semantics for cross-version comparison; hash
exact bytes for archival integrity. Require archive manifests and coverage validation;
do not assume every S3-compatible provider supplies equivalent retention controls.

## 3. Compare credible financial authorities

| Criterion | Managed log + state | Postgres + transactional outbox | TigerBeetle + workflow bridge |
| --- | --- | --- | --- |
| Financial commit | State, complete decision, Kafka input position | Financial rows, workflow, dedup, sequence, outbox | Linked ledger transfers; workflow completion separate |
| Shared resources | One owner per closed domain | Locks/serializable transaction + admission order | Ledger invariants; workflow still coordinated |
| Recovery source | Changelog or certified full state/decision cut | DB recovery + WAL/backups, outbox continuation | Ledger recovery plus bridge/workflow reconciliation |
| Reads | Async SQL, explicit freshness | Same-DB transaction can provide immediate bundle | General DB/materializer needed |
| Determinism | Must enforce continuation/input order | Must enforce same admission order through retries | Must control bridge order and time |
| Existing fit | Highest: current runtime/broker | Strong: accepted relational baseline | New technology and operational dependency |
| Main unknown | Complete evolve; one hot domain; SQL visibility cost | Same-workload rate and contention/WAL | Bridge failure semantics and logical time fit |

Recommendation changes if log candidate fails reconstruction/atomicity, cannot
meet required hot-domain rate with bounded recovery, or SQL projections dominate
full-system cost without useful separation. A failed log spike does not prove an
alternative works; compare the smallest equivalent kernel adapter.

## 4. Honest capacity baseline

| Retained observation | Proven scope | Boundary for new work |
| --- | --- | --- |
| CAL-P1-L9: 4,998.03 accepted commands/s, 300s, 749,952 trades/receipts including preflight | Local HTTP intake through Phase1 receipts, single hot matching lane | About 2.5k trades/s for paired orders. No later financial workflow, individual latency or fault-at-load proof. |
| CAL-P2-E4: 3m local joins / 300s with aged rows | Single-thread RocksDB/codec, WAL off | Excludes source decode/broker/Streams durability and SQL. |
| `sustained-8ea6c8ce`: 3.15m exact full contexts; producer 310.730s | Injected paired Go source, local RF3, one active lane | Frozen 301s producer bound failed; no all-profile qualification. |
| `recovery-5545850c`: million-row local-state recovery observed RUNNING in19.97s | Earlier resolver candidate, broker probe | Not financial-state reconstruction, final-candidate RTO or full-role startup. |

Original seed/oracle logs for `sustained-8ea6c8ce` and previous
`sustained-53a64664`, plus L9 raw report, inspected for this review. Other values
above retain their ledger/report attribution. Sources: [throughput ledger](../THROUGHPUT_BASELINES.md),
[resolver artifacts](../evidence/calcify-phase2-implementation/README.md),
[L9 report](../evidence/calcify-phase1-go-5k-upper-5m.json),
[E4 report](CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md).

No new timing result. Proposed workload adds actual shared-account state, journal,
reservations, due work, SQL financial bundle and API load. It differs materially
from source-injected resolver tests and historic commands/s results. Qualification
requires fixed workload, code, topology, hardware, offered/durable/output counts,
latency/freshness measurement and failure/recovery acceptance rules.

## 5. Confidence and remaining decisions

High confidence: authority must be explicit; shared-resource serialization,
complete future state, recorded time/order, finite history and independent read
progress are necessary. Medium confidence: existing managed stack is simplest
first candidate. Low confidence: any claimed full-path 10k capacity before proof.

Owner decisions before adopting financial architecture: physical authority;
domain/account sharing; replay/seed promise; finite retention and recovery budget;
fixed capacity/latency/freshness envelope. Propose defaults in RFC; do not repeatedly
reopen the agreed Phase1/2 source-only slice or turn every future capability into
its prerequisite.
