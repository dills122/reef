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
