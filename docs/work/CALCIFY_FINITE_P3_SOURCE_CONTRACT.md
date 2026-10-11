# Finite Calcify P3 source contract

O0 implementation contract, October 9/10, 2026 overnight session. Baseline
`51cd90eee43d257bad4f973fc9bd81f273bb4ef7`; branch
`codex/calcify-p3-overnight-2026-10-09`. This document freezes proposed finite
implementation scope; independent review and manager acceptance precede dispatch.
Execution status belongs in [WORK_PLAN](../WORK_PLAN.md). Requirements derive from
[overnight plan](CALCIFY_OVERNIGHT_SESSION_PLAN.md),
[RFC §5](CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#5-source-dependencies-and-admission),
[RFC P3](CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#10-proof-plan-and-stop-conditions), and
[rollout boundaries](CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#11-rollout-observability-and-decisions).

## Scope and frozen choices

One opt-in isolated run, one command/source partition, one session/instrument,
two explicitly mapped participant/accounts, security units plus quote nanos.
Unreserved gross DvP only. Existing production financial authority remains in
place. No reservation, netting, cross-domain resources, external effects, financial
cutover, capacity qualification, or seed-only reproducibility claim.

Choose source-prefix capture. One complete committed source batch is one window.
Read all source records from registered genesis/certified cut in partition order,
including zero-trade outcomes; never drive lifecycle capture from verified links.
Full batch closure arrives in same source record, so completion cannot hide behind
paused future headers. Dependencies must exist in prior prefix or earlier members
of same record. Unknown routing/coverage faults whole declared lane. Head-of-line
blocking is accepted; no durable credits or bypass indexes in this slice.

Keep `calcify-finite-source-v1` JSON canonical bytes/hash, existing 21/23-byte links,
unprofiled matcher snapshots and existing source fixtures unchanged. New source
fact and finite budget/binding have separate versions. Existing accepted-order
facts remain immutable; amendment does not overwrite original acceptance.

Finite P3 source policy `calcify-limit-day-v1` permits hidden `LIMIT`/`LIMIT_HIDDEN`,
`DAY`, positive integer
quantity/price and two distinct participants, each assigned one side in fixture.
This excludes self-trade and implicit IOC/FOK cancellation from initial proof.
Unsupported executed source is retained and faults, never discarded or declared
financially covered. Changing policy requires new binding/version and tests.
Pin session Open for entire finite fixture, all12 commands at fixed timestamps
within one UTC day (for example2026-10-10T03:00:00Z through03:00:11Z), with no
session-close/expiry/admin input. DAY uses current matcher resting-order path;
do not assume perpetual lifetime or silently ignore unexpected expiry lifecycle.
Existing API/protobuf/source contracts already support DAY; no new TIF enum needed.

## Actual gaps at baseline

`streamdirect.CommandOutcomeFact` contains command type/run/payload hash and generic
`SubmitOrderResult`. Successful cancel is `result.accepted`, not necessarily
`result.cancelled`. Successful modify omits amended quantity/price. No durable order
revision counter exists in these facts. `MatchContextResolver` indexes immutable
acceptances and trades, not every lifecycle outcome. Existing verified-led reader
therefore cannot establish zero-trade closure. Payload hash cannot reconstruct
missing command economics.

`PostgresStreamCommandIntakeStore.reserve` currently inserts with autocommit and
looks up conflicts separately. `handleStreamAckMutationResponse` returns HTTP202
after broker acknowledgement; unpublished retries can publish again. Broker append
followed by crash before publication marker is ambiguous. Existing finite P0 bounds
retained order IDs, not these attempts, publication retries or broker history.

## Source facts, membership and dispositions

Add optional checksum-covered `OrderLifecycleCommandV1` to Go source outcome,
only under new explicit lifecycle configuration requiring finite P0 profile.
Source producer copies already decoded command fields; no order-book/SQL scan.
Absent field must marshal identically to old source bytes. Keep existing semantic
checksum algorithm; optional typed fact participates in complete body checksum.
Pin cross-language old/new golden checksums. Legacy Phase1/2 still reads old history;
finite P3 refuses history lacking required lifecycle fact after its declared start.

Typed fact contains schema/version, command ID/type, run/session/instrument,
order ID, participant/account, occurred-at and finite binding/profile identities.
Submit additionally contains side/type/TIF/quantity/price/currency; modify contains
new total quantity and price; cancel contains reason. Routed session/instrument
must equal decoded authoritative values used by matching. Result disposition stays
separate from attempted command fields. Decode/unsupported poison cannot supply a
valid fact; finite lane faults at that source record with evidence retained.

New protobuf capture envelope contains immutable binding digest, exact source
provenance, ordered members, declared outcome/trade/member counts, source encoded-byte
count/content digest and completed source frontier. Each source record emits one complete
envelope, including an empty coverage envelope for valid zero-outcome source.
Avoid separate header/seal topics. Original source remains canonical matching
history; envelope is durable versioned capture with verifiable provenance.

Content digest hashes versioned canonical binding/provenance/member/count payload,
excluding digest field itself. Source byte count refers to original source value,
not enclosing envelope. Enforce envelope limit on final serialized bytes.

| Member | Required facts/dependencies | Terminal capture disposition |
| --- | --- | --- |
| Every command outcome | Exact outcome ordinal, command ID/type/payload hash/status, typed attempted fields and structured acceptance/rejection | `APPLIED` or `REJECTED`; rejected outcome has zero order/financial mutation |
| Accepted submit, including zero trades | Valid original acceptance plus typed command equality; no prior same-run acceptance | `APPLIED`; creates revision0 before nested trades |
| Accepted modify, including zero trades | Original acceptance, latest revision and previous order-effect member; positive new total quantity greater than already filled | `APPLIED`; creates next revision with new quantity/price before nested trades |
| Accepted cancel, including zero trades | Original acceptance, latest revision and previous order-effect member | `APPLIED`; next terminal revision, remaining quantity0; no ledger legs in unreserved model |
| Every nested trade | Both immutable acceptances, current economic revisions, previous effect for each order, positive integer economics and source command | `READY_EXECUTION`; exactly one financial action after O3 |
| Business rejection | In-budget routed command; structured reason, no trades/acceptance contradiction; known target dependencies when present | `REJECTED`, including not-found with explicitly absent target; no invented acceptance dependency |
| Valid source record | Every declared outcome/member processed, no missing/duplicate/contradictory member | `PREFIX_CLOSED`; capture coverage, never financial settlement completion |

Command member ID is structured `(generation, topic UUID, partition, offset,
outcome ordinal, COMMAND)`. Trade member adds within-outcome ordinal; retain flattened
trade ordinal matching existing `CommitmentId`. Revision ID references exact command
member plus per-order revision number. Dependency IDs use structured protobuf fields
or length framing, never ambiguous delimiter concatenation. Business execution key
remains `(run, session, instrument, executionId)`; physical source provenance is
separate. Filled/cancelled order IDs remain retained under P0 retention0.

Within outcome: apply accepted submit/amend revision, then trades in nested order,
then terminal order state. A trade updates filled units and previous effect pointer
for both orders; it does not rewrite revision economics. Accepted cancel follows all
prior effects. Rejects do not advance successful revision/effect. Reducer verifies
quantity conservation and no trade after terminal state. Unsupported implicit
counterparty cancellation faults rather than synthesizing incomplete lifecycle.

Exact repeat of same source/member and digest is no-op; changed repeat conflicts.
Different physical source member is not silently collapsed by command ID. Repeated
business execution ID with changed economics conflicts. Identical duplicate business
execution cannot create second financial obligation; O3 owns that disposition.
Validate entire source record before publishing any member or advancing checkpoint.

An identical repeated batch ID/checksum at another physical offset gets explicit
`REPLAY` member dispositions referencing first batch, new physical coverage and no
second revision/fill mutation. Changed repeated batch ID faults. Execution reuse
outside certified identical batch replay faults capture; do not increment filled
quantity twice and wait for O3 to catch it. Keep at most16 batch identity/digest rows
under publication budget. Same command ID at another offset with a different result
is not identical batch replay; preserve business rejection or fault contradiction.

## Small fixture and numeric budgets

Fixture uses run `calcify-p3-20261010-a`, session `calcify-p3-session`, instrument
`AAPL`, quote `USD`, buyer `p3-buyer`/`p3-buy-account`, seller
`p3-seller`/`p3-sell-account`. Bind these identities in fixture profile; launcher
substitutes unique run/application/topics before durable genesis registration.
Never share writable balances with legacy runs.

| Budget | Limit | Capacity reserved before acceptance |
| --- | ---: | --- |
| Retained order IDs | 8 (`p0-1`…`p0-8`) | Eight immutable acceptances/current states |
| Quantity / price | 10 units / 100000000000 quote nanos | Checked signed64 notional; no floats |
| Recorded finite-ingress attempts | 32 | Includes eligible retry/conflict/business refusal attempts |
| Possible broker command publications | 16 | Every permitted send, including uncertainty, charged before send |
| Command payload | 8192 UTF-8 bytes | Per publication |
| Outcomes per source batch | 1 | Dedicated finite matcher configuration |
| Trades per outcome | 8 conservative upper | One command member + at most8 trade members |
| Open capture windows | 1 | All9 members plus closure/control space |
| Source record | 65536 encoded bytes | 16 records →1048576 bytes raw source upper |
| Capture envelope | 131072 encoded bytes | 16 envelopes →2097152 bytes capture upper |
| Fetched suffix | Up to15 other finite source records | Full1048576-byte source upper reserved separately from open window |
| Finite attempt journal row | 4096 bytes | 32 rows →131072 bytes, separate from source history |
| Opening/binding record | 16384 bytes | Separate reserved control capacity |

These are enforced logical/serialized payload budgets, not measured JVM/RSS or
physical broker disk bounds. Physical record/batch overhead, replicas, indexes,
changelogs and client/native memory require preflight inventory. Pin no compression,
broker/topic batch ceilings and client fetch/producer limits; consumer
`max.poll.records` alone is insufficient. JVM/native/process resources require
measured finite preflight; absence of complete physical bound prohibits whole-process
capacity claims. No performance campaign needed for O1 correctness.

Small records may pack many records into one fetch; poll-count limits cannot cap
client's raw suffix to two records. Reserve entire finite source allowance for
already fetched/unopened suffix, then enforce decoded open-window limit separately.
Fetch/decompression/decoded amplification still belongs to physical preflight.

This bounds admitted/recorded attempts and required history, not arbitrary incoming
HTTP requests, network bytes, CPU or wall time. After stop, refusals create no finite
history; existing rate/concurrency limits still protect request processing.
Manager-proposed diagnostic resources: isolated RF1 Redpanda≤2CPU/2GiB,
Postgres≤1CPU/1GiB, one Java21 JVM≤768MiB; total owned allocation target≤4GiB.
These ceilings are resource leases, not a measured complete cost/capacity bound.
Broker start/live proof requires separate manager lease; no shared reset/deletion.

Deterministic fixture, all prices expressed in nanos:

1. Sell `p0-1`,3 units at100000000000; sell `p0-2`,2 at same price.
2. Buy `p0-3`,4 at same price: two executions of3 and1.
3. Modify `p0-2` to total4 at99000000000: zero trades; cancel `p0-2`: zero trades.
4. Buy `p0-4`,2 at98000000000; modify to100000000000: zero trades.
5. Sell `p0-5`,2 at100000000000: one execution of2.
6. Cancel filled `p0-1`; resubmit terminal `p0-1`; modify absent `p0-8`; cancel
   absent `p0-8`: four explicit business rejections, no mutation.

Expected12 unique command outcomes,8 successful lifecycle commands,4 rejections,
3 trades and6 executed units. Five retained order identities; `p0-2` cancelled
after1 filled unit, other four filled. Capture member count15. Add identical retries
and changed retry conflicts within32 attempts without extra publication for known
published replay. Test caps at exact limits and one over separately.

## Canonical ingress stop, drain and durable binding

Finite scoped run accepts through one durable stream-intake adapter. API/manual
simulator commands use same boundary. Command-log and accepted-async modes reject
scoped run before queue/store/matching work; ordinary direct matching HTTP/gRPC
mutation is fenced for dedicated finite matcher. Stream processor accepts only
registered command topic/lane. Broker producer permissions or equivalent tested
exclusive writer containment must prevent arbitrary direct producers bypassing
adapter. Activation refuses if this isolation cannot be attested. Launcher limits
and private-topic naming alone are insufficient.

Trust proof names actual authenticated producer/admin principals and deployment
containment: only finite adapter may WRITE command topic; matcher may READ commands
and WRITE source; capture may READ source/WRITE capture/changelog; untrusted clients
cannot access direct engine listeners or broker credentials. Topic DELETE/ALTER
belongs to controlled operator while stopped. Verify denied publish/admin operation
with another principal plus rejected direct HTTP/gRPC mutations. Unauthenticated
local broker requires tested process/network containment providing equivalent
exclusion; otherwise live gate remains open. An attestation string is not proof.

New `calcify-finite-budget-v1` canonical document is separate from frozen P0 JSON.
Canonical bytes start ASCII `calcify-finite-budget-v1` plus NUL, then raw32-byte P0
digest; length-prefixed UTF-8 source policy, run, session, instrument and currency;
u32 account count then sorted `(participant, account)` mappings, each two framed
strings plus side byte1=BUY/2=SELL; then u64 budgets in this exact order: recorded
attempts, publications, command bytes, outcomes/batch, trades/outcome, open windows,
source bytes, capture bytes, attempt bytes, opening bytes. All lengths/integers
big-endian; integers positive and≤signed64 max. String frames use u32 byte length,
no normalization; reject duplicate mappings/unknown JSON fields. JSON is input
adapter; SHA256 canonical bytes is budget digest. Commit golden bytes/hash before
use. This does not change P0 struct canonicalization. Binding covers P0 digest,
budget version/digest, source policy, run/account/assets, partition routing, command/
source/capture topic names and UUIDs, source generation, application/state version,
genesis or certified restore mode and exact starting vector. Persist registration
before listeners/admission start; source capture validates same binding on every
envelope. Mutable counters/frontiers are state under that binding, not identity.
Changed/missing binding refuses activation; no first-start inference from partially
retained history. Existing source-generation/topic registry remains in force.

Producer lifecycle mode changes recovery output even when matching state stays
identical. O1 is opt-in fact/capture contract proof; producer snapshot/canonical-log
restore activation proof belongs to O2. O2 leases `internal/app/service_snapshot.go`
and profile/startup wiring: add separate optional checksum-covered finite binding
identity under versioned binding contract, following existing optional profile hash
pattern. Mode-off snapshots keep exact bytes/checksums. Lifecycle-enabled restore
requires matching binding; unbound legacy/profile-only snapshot refuses, and bound
snapshot refuses mode-off restore. Canonical command recovery verifies same durable
mode/binding before replay; no silent reinterpretation of old unbound histories.
Keep P0 v1 struct/hash unchanged; test old fixtures plus missing/changed/new binding.

Use existing Postgres durable stream-intake seam. Finite transaction locks one
binding/budget row, checks attempt/publication/byte reservations, records bounded
attempt disposition and updates/reserves existing stream-intake row atomically.
Lookup by indexed binding/command/idempotency keys only; no history scan on hot path.
Attempt includes authenticated syntactically scoped request reaching this adapter;
transport/auth/syntax failures cannot claim run membership and never create finite
command/source history. Eligible scope/policy/guardrail refusals, identical retries
and payload conflicts consume attempt capacity and have explicit bounded ledger
dispositions. Guardrail rejection must not create accepted command/stream row.
Arrange finite check before scoped durable guardrail audit, or atomically share
budget with that audit; otherwise reject activation rather than allow audit flood.

At32 attempts,16 possible publications or insufficient reserved bytes: latch
`ADMISSION_STOPPED`; later requests return bounded refusal without journal append,
publication, queue mutation or budget reset. Return known published retry's original
receipt only if policy explicitly permits read-only replay after stop; it creates
no new attempt history. Controls required to close already admitted work have
separate pre-reserved capacity. Fixed whole-run source allowance reserves all16
possible completions up front, so pause does not consume closure reserve.

Before invoking broker publisher, persist `PUBLISHING` and charge possible append.
Broker acknowledgement changes row to `PUBLISHED` with exact topic/partition/offset;
only then HTTP202. Timeout/ambiguous failure changes row to `UNKNOWN`, latches scoped
new-admission stop, retains permit/payload and allows source drain. Do not blindly
republish unpublished row under ordinary stream retry behavior. Late definite ack
can certify publication. Distinguish definite pre-send rejection from ambiguous
delivery; never reclaim possible append permit without proof/fencing.

Crash after reservation/before send is also uncertain on restore. Fence old producer,
inspect bounded registered command history during startup/offline reconciliation,
and certify presence/absence plus old-writer fencing before retrying any unsent
work. Every allowed replacement send consumes another remaining publication permit.
If this evidence is unavailable, stay stopped while draining known accepted source;
do not label unresolved publication as accepted or safely absent. Actual broker
transaction/restart assertions require isolated live proof; doubles prove model
behavior only. This bounded uncertainty stop avoids introducing an outbox redesign.

New keyed updates/journal operations change finite admission cost. Before live
activation, O2 records statement counts, lock/error behavior and finite stage costs
on actual durable path; no new unmeasured production hot-path writer or scan.
If existing store cannot atomically host these responsibilities within lease/time,
return research evidence and smaller reviewed O1 checkpoint. Do not weaken budgets.

## Capture state, source-prefix closure and restore

Implement separate finite capture topology/reducer with managed store and EOS
output/checkpoint; reuse existing Streams patterns, not verified-led demand resolver.
Persist binding/state version, immutable acceptances, current revisions/filled
quantities/terminal states, previous effect pointers, member dedup/digests, source
cursor/resume offset, completed coverage frontier and bounded open-window/suffix
state. Do not retain unbounded full decoded source/history in cache.

Atomic unit is complete record validation → reduction → envelope output → managed
state/input checkpoint. Invalid member creates retained lane fault and prevents
partial output/checkpoint. Transaction abort/rollback must invalidate non-managed
caches. Pause only after staged window/suffix is accounted; closure of opened window
remains runnable. No state/output outside certified transaction.

Numeric Kafka offset gaps are legal: advance to actual next read-committed record,
not offset+1 completeness assumptions. Resume offset is broker next position;
coverage frontier names last actual closed source record. Starting cut must retain
all prior required acceptances/revisions or certified state. Missing checkpoint does
not authorize current earliest offset as new genesis.

Restore verifies full binding and required history range/UUIDs, restores exact
attempt/publication reservations and uncertainty stop, source/capture positions,
pending windows/suffix/member state and all lifecycle dependencies. Every earlier
application record is represented once by disposition/completed state/pending work.
Zero-trade records contribute to source frontier. Lost required history, changed
budget/profile/state version, incomplete accounting or incompatible cuts refuse
activation. No app-ID reset, source-generation bump or counter-zeroing as recovery.

## Implementation map and dispatch gates

| Unit | Existing seam / new responsibility | Required focused acceptance |
| --- | --- | --- |
| O1 producer fact | `internal/streamdirect/processor.go`: `CommandOutcomeFact`, `processedOutcome`, `processDelivery`; new typed fact under `internal/domain/`; opt-in config in stream runtime | Real submit/modify/cancel zero/multi-fill facts; routed scope; old absent-field checksum bytes; changed typed fact checksum; malformed/unsupported faults |
| O1 contract/capture | `contracts/proto/calcify.proto`, `contracts/calcify/`; new finite lifecycle parser/reducer/topology/tests under platform `calcify/`; reuse `CalcifySourceBatch.checked` checksum validation and `CalcifyResolverProcessor` EOS/store patterns | Fixture exact15 members/3 trades; revisions/effects; offset gaps; missing/changed/duplicate/cross-run members; late rejection; atomic malformed record; poll batch variation; abort/restore cache parity |
| O2 durable ingress | New finite adapter; `StreamCommandIntake.kt`, `StreamCommandContracts.kt`; leased `PlatformHttpServer.handleStreamAckMutationResponse`/mode routing; migration under `scripts/dev/db/migrations/boundary/` and schema requirements | Concurrent last-token reservation; retries/rejections/conflicts; no202 before ack; crash at reserve/send/ack/mark; UNKNOWN stop; full accounting/binding restore; indexed queries/no scans |
| O2 isolation/launcher | New finite launcher under `scripts/dev/calcify-financial/`; matcher `cmd/matching-engine/main.go` plus transport fences under explicit lease; source registration/bootstrap | Manual/API/accepted-async/command-log/direct-stream bypass refused; exact topic/lane writer proof; future-window flood leaves admitted closure reachable; pin limits and refuse changed/missing history |
| O3 financial input/kernel | New main-source financial module; test-only `financial/FinancialKernel.kt`/`FinancialCanonicalLeafOwner.kt` reference, not wholesale harness copy | Common durable order for source/funding/repair; typed integer balanced legs; mapped identities; exact retry/conflict/once-only obligation; pending has zero partial legs |
| O4 SQL/read | Separate scoped migration/projector/repository; existing settlement API/gateway under lease | One SQL transaction rows+checkpoint; epoch/version fence; crash after commit once; run/account authorization; common read snapshot and progress; no full-history hot-path rescan |
| O5 integrated fixture | Finite launcher/integration proof plus owner docs/evidence | Source/admission/owner/SQL restore; funding+distinct repair settles once; exact API rows/progress and journal; stage costs with finite scope |

O3 budgets reserve worst-case128 source executions (16 publications×8) plus8 controls,
at most160 admitted financial attempts and320 result/history records; integer account
positions four (two accounts×two assets), at most256 obligations before retirement.
Pin serialized row/record and physical bounds in reviewed O3 packet before enabling
financial runtime. Fixture opens buyer quote0/seller security6; three trades pending
with zero legs, funding buyer600000000000 quote nanos plus three distinct repair
attempts settles all3 once. Opening, funding and repairs use same durable financial
input order; control identity/digest and authorization are mandatory.

Existing settlement reads authenticate general route access but
`settlementReadResponse` currently delegates scenario run directly after
`apiV1ReadErrorResponse`; O4 must prove run/account object authorization rather than
infer it from route presence. Existing relational settlement authority remains
assigned to its original runs; isolated Calcify SQL bundle uses separate namespace.

O1 may implement after this spec's accepted review. O2 starts only after accepted
O1 and requires separate code review/evidence; O3→O4→O5 only after reviewed
predecessors. Heavy pivot or failed enforceability returns research spike/decision
gate; no live P3 completion claim from pure reducer/model tests.

Source evidence: graph `reef-main`, generation2026-09-30, different checkout, provided
positive leads only. Coverage marked Calcify contract/pipeline changed and new
profile/protobuf not tracked; current source reads above establish these bounded
claims. No exhaustive graph or fresh broker/SQL qualification asserted. New active
planning contract, no superseded records removed; retention archive pass no-op.
