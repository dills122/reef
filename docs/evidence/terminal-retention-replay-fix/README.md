# Terminal retention replay fix evidence

Scope: isolated matcher fix, based on merged `1e77575a9e916f022beb81b5f8927be962cceeff`.
Date: 2026-10-01 America/Toronto. Toolchain: Go 1.26.5, darwin/arm64.
No broker stack, new throughput run, or JSON-library treatment used.

## Reproductions

- [Original red](red.log): limit 1; run A submits/cancels order A, run B
  submits/cancels newer order B. Repeated cancel A returns `NOT_FOUND` if B
  interleaves first, `INVALID_STATE` if B follows, despite identical A-lane commands.
  Also proves old snapshots admitted enabled retention and changed limits.
- [Batch-cut red](red-batch-cut.log): same book submits/cancels A then B,
  repeats cancel A. One publication batch returns `INVALID_STATE`; separate
  commits return `NOT_FOUND`. Deferred retention caused this second defect.
- [Review regression red](red-provisional-reservation.log): first immediate
  eviction design let another book accept an evicted order ID before publication;
  rollback could overwrite that committed record. Final design holds the
  shard-global reservation until the owning batch closes.

All red runs intentionally exit 1. They are counterexamples, not final suite
failures. Snapshot red tests target unavailable policy proof/settings identity,
not corrupt bytes or missing command reconstruction.

## Correction and test record

Per-book heaps apply retention at each terminal transition. Batch-local indexed
heap undo costs `O(log N)` per touched transition, with no N-entry copy. Evicted
order records enter the existing undo journal before release. Provisional ID
reservations prevent another book from acquiring an ID that rollback may restore;
same-owner reuse across the batch's owned books remains allowed. Commit/rollback close idempotently.

New snapshots include exact retention policy/limit in V4 checksum. Old snapshots
remain readable only with retention disabled; missing terminal facts cannot be
inferred. See [recovery compatibility](../../../services/matching-engine/README.md#terminal-retention-and-recovery-compatibility).

Tests cover all three book dimensions, full repeated rejection equality,
within-book eviction, live versus restored outcomes, different publication batch
cuts, older-timestamp self-eviction, same-batch evicted ID reuse across books with one global index preimage, unrelated book
commit, rollback/retry, fill/STP rollback, global provisional ID ownership,
post-commit ID release and snapshot policy/checksum/limit guards.

During development, initial focused checks required changing assertions that
still described deferred/global retention and explicitly supplying limit 128 to
restore the concurrency-test snapshots. [First broad race run](first-race-policy-assertion.log)
found one additional old stream-direct assertion expecting cross-book eviction;
updated it to assert lane-local state and exact command outcomes. A subsequent
[pre-reservation full race run](green-race-pre-reservation.log) passed all packages.
[Intermediate reservation API compile check](first-reservation-api-build.log)
found old index unit callers; retained the existing non-batch `reserve` API and
added explicit owned-batch reservation API. Provisional-reservation reproduction
initially used an absent public `OrderState.RunID` field; corrected it to inspect
internal record scope before capturing its behavioral red failure.

A second [review counterexample](red-multibook-preimage.log) proved that restricting
same-owner reuse to one book changed outcomes when a multi-book batch was split.
Final correction permits reuse across books owned by the same batch and journals
one initial global order-index preimage per ID. Per-book undo restores books;
index restoration then runs once per ID, independently of map iteration order.
Both preexisting active IDs and IDs first created inside the batch are covered.

Final [focused checks](green-focused.log) passed in app and stream-direct packages.
Final [whole matcher race suite](green-race-all.log) passed all packages with
`go test -race ./... -count=1`, exit 0, after both review corrections. No race
diagnostics. [Manifest](manifest.json) records commands and source/log hashes.

## Remaining boundaries

- Positive N bounds N terminal records per retained book, not total engine memory;
  book count and active order count remain unbounded by this knob.
- Batch callers must retain exclusive mutation ownership of each touched book
  through publish and close. Indeterminate publish requires fencing, not rollback.
- Engine-wide order-ID namespace remains unchanged. Cross-book deliberate ID
  reuse after a durable eviction is not a promise of replay independence; Calcify
  separately rejects new acceptances reusing a generation/lane ID.
- Old canonical outcomes cannot be retroactively corrected by this patch. V4
  binary rollback requires a compatible old checkpoint plus canonical replay;
  qualify run/generation migration before switching historical replay policies.
- No production capacity, broker durability, hosted fault-at-load or JSON speed
  claim follows from these local correctness tests.

## PR #438 master integration

Original evidence above describes source at `9778e847` on base `1e77575a`; original manifest and logs remain historical. Master `92cdd20b` includes PR #436 run-scoped order identity. Updated reservations/preimages use `(runId, orderId)`; previous engine-wide namespace boundary is superseded for different runs. [Review and integration evidence](pr-review-2026-10-01/README.md) records OCR triage and final checks after conflict resolution.

Second [OCR review pass](pr-review-pass2-2026-10-01/README.md) assesses three additional unsupported claims, adds default-environment restore and multi-entry rollback coverage, and records passing matcher race suite after master24cdc1de sync. Matcher runtime safeguards unchanged.
