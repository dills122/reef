# Calcify Phase 1 link contract

Version 1 uses fixed-size, big-endian binary records. Kafka keys are null. Each output record uses same numeric partition as input. Matching publisher also places each source batch on its declared command-lane partition; extractor rejects a mismatch between batch metadata and Kafka partition. No trade economics, trade ID, batch ID, checksum, or duplicate ID field is stored in link.

| Record | Bytes | Layout |
| --- | ---: | --- |
| MatchCommitment | 21 | version `u8=1`; sourceGeneration `i32`; sourcePartition `i32`; sourceOffset `i64`; flattened tradeOrdinal `i32` |
| CommitmentVerificationPassed | 23 | exact 21-byte commitment link; stub policyVersion `u16=1` |

All identity components must be nonnegative except sourceGeneration, which must be positive. Four source components are commitment ID and source pointer. Trade ordinal starts at zero and increments across every nested `TradeCreated` in outcome order within one committed `VenueEventBatch`. Zero-trade batch has no link; extractor still commits source offset transactionally. Extractor groups valid partition prefixes from one bounded poll (at most 100 source records) into one Kafka transaction with their source offsets. Malformed source record emits no partial links; its partition stops at that record while healthy partitions continue. Verifier pass records only link and policy version. Policy 1 checks link shape and partition; it makes no business-eligibility or settlement claim.

Wire version or field-layout changes require new contract version and stream. Source generation increments on venue-event topic recreation. PostgreSQL `runtime.calcify_source_generations` registry maps generation to topic name and binds its broker topic UUID on first extractor start; extractor refuses unregistered or mismatched generation. Operators must stop extractor before topic recreation, register next generation, then restart it; same-generation startup or reassignment refuses changed broker topic UUID.

Fixture mapping, with sourceGeneration 1 and sourcePartition 2:

| Source fixture | Source offset | Nested trade counts by outcome | Emitted ordinals | Payload bytes per link |
| --- | ---: | --- | --- | ---: |
| zero | 80 | empty batch | none | 21 |
| one | 81 | 1 | 0 | 21 |
| many | 82 | 2, 0, 3 | 0, 1, 2, 3, 4 | 21 |

`CalcifyContractTest` constructs checksum-valid source fixtures and asserts mapping, wire sizes, round trips, and malformed-input rejection. Contract is deliberately a compact binary link rather than a Protobuf trade payload; original matching facts stay in source event stream.

## Finite P0 matching source profile (2026-10-07)

Optional `MATCHING_ENGINE_CALCIFY_SOURCE_PROFILE` JSON, schema
`calcify-finite-source-v1`, binds one or two explicit run IDs, one venue session,
one instrument/quote, finite order-ID range and quantity/price caps. [Example](finite-source-profile-v1.json).
Startup requires explicit `MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT=0`
and matching instrument quote. Unknown/malformed fields, missing/oversized limits
or duplicate run IDs refuse startup. Ordinary matching remains opt-out.

Order IDs are exactly `p0-1` through `p0-maxOrderIds`, with canonical decimal
suffix (no leading zeros). Explicit nonempty command IDs and timestamps required
for deterministic replay; maximum10000 IDs per run, two runs, quantity1000000
and price1000000000000. Command text fields cap128 UTF-8 bytes. Scope/range/amount
violations yield `CALCIFY_SOURCE_PROFILE_REJECTED` before order mutation. Filled
and cancelled accepted IDs remain retained and yield `DUPLICATE_ORDER_ID` on
same-run resubmit; different declared runs may independently use same raw ID.
Rejected submissions do not consume IDs. Submit, modify and cancel use existing
manual/stream command paths; no extra ID store or admission counter.

Input JSON key order, whitespace and equivalent string escapes do not affect hash.
V1 struct field order and sorted run IDs are frozen compatibility bytes; new
fields or canonicalization rules require explicit version/migration design.

Snapshot V4 adds optional checksum-covered `calcifySourceProfileHash`. Restore
requires identical normalized profile (run-list order ignored), retention0 and
in-budget scopes, book entries and order records. Unbound legacy snapshots refuse
profiled restore; profiled snapshots refuse unprofiled restore. Collocated profiles
require whole-service snapshots; scoped partial-run snapshots are unavailable.
Unprofiled snapshots keep existing bytes/checksums. Canonical command recovery
requires exact frozen profile/configuration; this local gate does not add durable
broker-level profile registration or financial lifecycle/coverage facts.

Structural bound: at most `runIds.length * maxOrderIds` retained order records
and resting entries across at most `runIds.length` books, with bounded record
strings. This bounds matcher state, not total command attempts, broker history,
wall time or measured heap. Finite command/history/closure budgets remain next
source/lifecycle work; financial authority and capacity qualification unchanged.

## Resolver run scope and hidden limit compatibility (2026-10-02)

`TradeSourceV1.run_id` (additive protobuf field 3) carries authoritative run identity
from the checksum-covered `CommandOutcomeFact.runId`. Matching publishes this field
from the decoded SubmitOrder, ModifyOrder or CancelOrder command, including modify
outcomes without accepted-order facts. Successful acceptance outcome run must match
`AcceptedOrderFactV1.run_id`; both trade acceptances must match trade run. Resolver
keys full immutable acceptances by source generation, run ID and order ID within
its partition-owned store. UTF-8 byte lengths delimit both IDs; IDs containing
colons remain unambiguous. Different runs may reuse order IDs; conflicting immutable
facts within one run remain a durable fault.

For old SubmitOrder source records, `result.acceptedOrder.runId` supplies the same
authoritative run. Old trade-producing ModifyOrder records without outcome run
metadata cannot safely identify a run; resolver faults instead of guessing from
available order IDs. Current producer fixtures cover both submit and modify paths.

`LIMIT_HIDDEN` is a public alias for hidden limit behavior, identical to `LIMIT`.
Both checksum-valid source spellings decode to `ORDER_TYPE_LIMIT`; original source
bytes/checksum remain unchanged. Unknown order types still fault. Canonical and
alias facts therefore share immutable acceptance equality during replay.

Managed resolver state version 2 adds run-scoped acceptance keys and run-bearing
target checkpoints. Empty stores acquire version 2; restored nonempty stores with
missing/different version fault and retain index, cursor, pending and fault suffix
evidence. No automatic key rename, fault clearing, source-generation bump or new
application namespace is allowed. See recovery procedure in
[Phase 2 implementation](../../docs/work/CALCIFY_PHASE2_IMPLEMENTATION.md).

## Finite lifecycle capture model (O1, October 10, 2026)

Additive `OrderLifecycleCommandV1` describes decoded Submit/Modify/Cancel attempts.
Go source JSON field `lifecycleCommand` is present only in explicit lifecycle mode;
mode off omits field and preserves prior semantic checksum/bytes. New typed fields
participate in existing full-body checksum. Capture consumes every full source
record directly, including zero-trade/empty records. Existing Phase 1 links and
verified-led resolver remain unchanged.

Source fact binds schema, profile hash, separate finite binding digest, decoded
ownership, routed run/session/instrument/order, command identity and exact metadata.
Submit retains side, type spelling, TIF, quantity, price and currency. Modify retains
attempted quantity/price; Cancel retains reason. Participant/account come from decoded
command, validated against immutable mapped roles. Modify/Cancel quote/security
context comes from retained acceptance plus instrument binding; command facts contain
no invented currency. Attempted facts attached to rejected Submit never become
immutable acceptance. Parser rejects duplicate JSON keys, absent/unknown typed
fields, wrong types, outer/typed disagreement, bad checksums/bindings, unsupported
policy and contradictory acceptance/rejection/trade/execution shapes. Source value
must be one JSON object followed only by whitespace; trailing roots/scalars/garbage
create retained lane fault. Attempted quantity/price remain bounded decoded strings,
including original spelling; positive numeric caps apply to accepted state and trades,
not rejected attempts. JSON numbers cannot replace required string fields.

`FiniteLifecycleCaptureV1` emits complete ordered command and trade members with
structured source/topic UUID/ordinal identity, original acceptance, economic revision,
previous effects, explicit disposition and dependencies. Trade members retain both
canonical `ExecutionCreated` facts, including source maker/taker roles. Accepted
Submit creates revision0; Modify/Cancel advance revision, preserving original
acceptance; trades update filled quantity/effect without changing revision economics.
Rejected outcomes never mutate orders. Quantity conservation, current limit prices,
ownership and terminal state checked before any output. Identical physical replay
is no-op; identical batch/checksum at new offset emits `REPLAY` members referencing
first batch, new coverage and zero repeated mutation. Changed batch replay or reused
execution outside certified replay faults. Offset gaps are legal.

Envelope `content_digest` is lowercase SHA256 of ASCII
`calcify-finite-capture-content-v1` plus NUL plus protobuf serialization with digest
field empty. No maps; repeated fields follow explicit source order. Source byte count
and SHA256 refer to original value bytes. Final envelope serialization capped131072
bytes. Coverage frontier names last actual closed source record; `resume_offset`
names next broker position. Closure proves source capture, never financial completion.

Default model caps: 16 source publications, 1 outcome/record, 8 trades/outcome,
1 synchronous open window, 8 retained orders, 65536 source bytes/record,
131072 capture bytes/record. Entire finite suffix reserves1048576 source bytes;
faulted record plus at most15 other records retained. Closed plus retained records
never exceed16. Managed state caps orders8, batch identities16, execution IDs128,
completed records16, source bytes1048576, capture bytes2097152 and total encoded
state6307840 bytes (two bounded copies of capture history, source suffix, order
dependency allowance and control allowance). These are logical serialized bounds;
Kafka fetch ceilings are soft for first oversized batch, so physical fetch/native/JVM
bound and producer/topic ceilings remain O2 preflight gates.

Managed model topology validates whole record against immutable state before
forward/store writes. No decoded cache. Rollback re-reads managed bytes. Fault persists
lane barrier and bounded raw suffix; successful coverage/resume/order state stays at
last closed record, with no partial envelope. Streams may checkpoint retained fault
input separately under EOS; source remains canonical and suffix explicitly accounts
for input beyond successful cut. Unretainable/overflow suffix throws and requires
transaction abort/replay. Certified restore reconstructs bounded receipts and checks
every acceptance/revision/effect, execution identity, batch, byte counter and frontier.
Fresh capture and receipt reconstruction share result identity/time, immutable submit
acceptance, complete trade provenance/economics/execution pairs and current order
transition checks. Trade receipts must match their accepted non-cancel command outcome.
Recomputed receipt digests do not authenticate source history; O2 still owns durable
source correspondence and activation proof.
Missing/changed binding, dependency history or source range refuses model initialization.
Empty store requires explicit registered genesis model cut; earliest-offset inference
and app-ID reset forbidden.

`FiniteLifecycleCaptureRuntime.run()` explicitly refuses live activation. Model
topology/Properties use EOSv2; TopologyTestDriver and managed-byte rollback/reopen
tests prove model behavior only. Durable binding/admission, authenticated writer
isolation, broker transactions/restart and operational activation remain O2 gates.
No default production wiring, broker start or financial authority change.

Actual Go `Processor.ProcessOnce` fixture
[source](finite-lifecycle-source-v1.jsonl) and [manifest](finite-lifecycle-source-v1-manifest.json)
pin12 outcomes/8 applied/4 rejected/3 trades/6 units/15 members/5 retained identities.
Fixture parameterization uses `p3-run`, `p3-session`, `buyer`/`buyer-account` and
`seller`/`seller-account`, instrumentAAPL/quoteUSD; semantic sequence and caps match
[finite source contract](../../docs/work/CALCIFY_FINITE_P3_SOURCE_CONTRACT.md).
Manifest digest is model-only, never durable registration proof. Source fileSHA256
`1a8cf1c8c769e3e2bf32fa80792f36da206f07e6e27ab4bbbc9511c925fb1811`.
[Paired mode-off source](finite-lifecycle-legacy-source-v1.jsonl) remains16647 bytes,
SHA256`bdd4df11f562ba2f4dd217168a3e81e60f96fa4c3fa4a25c01e75830dda26506`.

[Finite budget canonical bytes](finite-lifecycle-budget-v1.hex) are260 bytes,
SHA256`6d49b6dd66e9cfa099e7b1c3cd02c9c275a9bf401d92069aace9634b5b4feee3`:
ASCII version plus NUL, raw32-byte P0 digest, framed policy/run/session/instrument/
currency, sorted framed party/account plus side byte, then big-endian u64 attempt,
publication, command-byte, outcome, trade, window, source-byte, capture-byte,
attempt-row-byte and opening-byte caps. Length frames use big-endian u32 UTF-8
lengths, no normalization. P0 v1 profile/hash unchanged. Internal model binding
identity additionally frames all three topic names/UUIDs, application/state version,
generation/partition/genesis and matcher caps; this rebuildable model identity is
separate from future durable O2 registration contract.
[Command capture wire](finite-lifecycle-capture-command-v1.hex) and
[multi-fill capture wire](finite-lifecycle-capture-multi-fill-v1.hex) pin actual source
reduction output. Focused tests validate wire bytes, metadata, compatibility,
counts/dependencies, malformed atomicity, gaps/replay, exact caps, fault suffix,
managed rollback/reopen and changed/missing restore history.
