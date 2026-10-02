# Calcify Phase 1 & 2 — deep architecture review

Status: independent post-merge review. Read-only; no code changed by this review.
Scope: everything shipped for Calcify through PR #430 (Phase 1), #431/#433 (Phase 2),
merged to `master` as of `1e77575a` (2026-10-01). Covers matching-engine (Go)
run scoping, the Kotlin/Kafka-Streams resolver, Phase 1 extractor/verifier/
receipt pipeline, the wire/proto contracts, test coverage, CI enforcement,
and the capacity evidence trail.

Reviewer note: this review is deliberately skeptical of the project's own
"passes"/"qualifies" language. Where the underlying evidence already admits
failure, that is quoted directly rather than summarized charitably.

## Executive summary

Calcify Phase 2 (PR #433, "feat(calcify): add Phase 2 full-fact resolver and
run-scoped matching") is on `master` today in the exact state its own authors
described as a **draft that must not merge**. The 2026-09-30 nightly handoff
(`docs/work/handoffs/2026-09-30-calcify-phase2.md`) is explicit: *"Draft must
remain unmerged until blockers below close"* and lists two P1 (severity-1)
correctness defects plus a sustained-capacity qualification that failed every
attempt. The squash commit merged to master is the identical commit set
enumerated in that handoff — no further fix commits exist between the
handoff and the merge. Both P1s are confirmed still open in the code, one of
them hidden behind a misleadingly-named class that could fool a shallow diff
review into thinking it was fixed (see Finding 1).

Independently of that process failure, this review found a defect in the Go
matching engine that the project's own prior review missed: the resolver's
entire indexing strategy is built on the premise that **order IDs are not
globally unique, only unique per run** ([docs/research/CALCIFY_PHASE2_ARCHITECTURE_REVIEW_2026-09-29.md](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/CALCIFY_PHASE2_ARCHITECTURE_REVIEW_2026-09-29.md)
Piece 2: *"Order IDs are not assumed globally unique unless the contract
proves that"*). The order-ID index in `services/matching-engine` was never
updated when run-scoping was added to the order book — it still enforces
*global* uniqueness and will reject a legitimate order in Run B if Run A
happens to reuse the same order ID. This inverts the premise the resolver is
built on, is silent (fails closed as a rejection, not corruption, but is
still wrong), and is completely untested — no test anywhere in the repo
submits the same order ID under two different run IDs (Finding 2).

Beyond those two headline issues, this review found: CI never actually
exercises the tests that would catch the two P1s or any real broker/Postgres
failure mode (Finding 3); the 10,000 resolved-commitments/s target has never
been met in a single run that passed every frozen gate (Finding 4); Phase 1
has the identical retention-loss exposure Phase 2 was blocked on, just never
gated on fixing it (Finding 5); and the ten-item Calcify decision register
(product model, financial finality, read contracts, recovery policy,
physical authority, capacity envelope, cutover) remains entirely open with
zero entries in `docs/DECISIONS.md`, while two phases of real infrastructure
have already been built on top of it (Finding 9).

None of this means the engineering is sloppy — the opposite is often true:
the exactly-once transaction boundary in the resolver is well-built and
tested with real crash injection, the Phase 1 receipt idempotency logic is
rigorous, and the project's own evidence docs are unusually honest about
failure. The problem is that known failures were not treated as blocking
before merge, and this review found additional failures the project's own
process did not catch.

## Findings, ranked by severity

### Finding 1 (P0 — process). Draft PR merged to master with two documented P1 correctness blockers unfixed

**Claim.** PR #433 was merged to `master` at `1e77575a` in the exact state
its own handoff said must not merge, and both documented blockers remain
open in the code today.

**Evidence.**

- `docs/work/handoffs/2026-09-30-calcify-phase2.md:9`: *"Draft must remain
  unmerged until blockers below close."* Same file, "Blockers And
  Limitations": both P1s listed as *"Fix not started."*
- `git log --oneline 79dab22b..1e77575a` shows exactly one commit: the
  squash merge of #433. `git show --stat 1e77575a`'s trailing subcommit
  message (preserved in the squash body) says so itself: *"Sustained
  qualification and verified/output recovery guards remain open in draft
  checkpoint."*
- **P1-A (verified-input retention can silently skip commitments) — confirmed
  unfixed.** `CalcifyResolverRuntime.kt:57` still sets
  `consumerPrefix("auto.offset.reset")` to `"earliest"` for the verified-topic
  consumer. The prescribed fix (`review-findings.md`) was `auto.offset.reset=none`
  plus a rebalance-listener gate validating `beginning <= checkpoint <= end`
  before consuming. **A class named `ResolverConsumerGate` does exist**
  (`services/platform-runtime/src/main/java/com/reef/platform/calcify/ResolverConsumerGate.java`)
  and is wired in at `CalcifyResolverRuntime.kt:61,65` — but it implements an
  unrelated feature: pause/resume flow control for lane-fault backpressure
  (`setBlocked`/`poll` override, lines 48-70). It has no
  `ConsumerRebalanceListener`, no `beginningOffsets()`/`endOffsets()`/`committed()`
  call anywhere in the file. **This is the single most important finding to
  flag to future reviewers**: the prescribed fix's exact class name was reused
  for a different purpose, which is exactly the shape of thing that makes a
  diff look fixed when it isn't. Contrast with `BrokerVenueSourceReader.kt:45,49`,
  which *does* call `beginningOffsets()` and fails closed — but only for the
  **source** topic, which was never the gap; the verified topic (the actual
  P1-A subject) has no equivalent check anywhere.
- **P1-B (verified/output topic identity not bound) — confirmed unfixed.**
  `ResolverSettings.kt` has exactly one topic-identity field, `sourceTopicId`
  — there is no `verifiedTopicId`/`outputTopicId` field, so there is
  structurally nowhere to persist the prescribed per-topic UUIDs.
  `CalcifyResolverRuntime.ensureTopics` (`CalcifyResolverRuntime.kt:94-119`)
  checks partition count, replication, and byte caps for the verified and
  output topics by **name**, never by topic UUID (contrast
  `CalcifySourceRegistration.kt:14` and `BrokerVenueSourceReader.kt:37`, which
  do check `topicId()` — but again, only for source). Line 101-103 creates a
  missing output topic purely on name-absence, with no check against a
  surviving changelog that assumes a different (deleted) output topic's
  history — exactly the silent-data-loss mode the review warned about.
- Test coverage for both failure modes is also absent: the real-broker fault
  probe's `"retention"` and `"recreate"` modes
  (`CalcifyResolverBrokerProbe.kt:219-220`) operate exclusively on the source
  topic; no test anywhere truncates or recreates the verified or output
  topics.
- This also violates the repo's own `docs/ENGINEERING_DELIVERY_POLICY.md`
  "Definition of Done Gate": *"no regression in legacy/compatible behavior
  paths"* and the non-negotiable test rule that new behavior ships with
  tests for it — two known failure modes shipped with explicit "fix not
  started" status and zero negative-path test coverage.

**Why it matters.** This is not a hypothetical: if the verified topic's
retention window is ever exceeded while the resolver lags (exactly the
scenario Phase 1's own stress tests show can happen under sustained load —
see Finding 4), the resolver will silently reset to earliest-available and
skip commitments with no error, no alert, and a healthy-looking `/readyz`.
If the verified or output topic is ever recreated (operational mistake,
disaster recovery, topic-config change), the resolver can silently resume
against a different topic's history or lose already-published output while
reporting itself healthy. Both are the kind of defect that is invisible
until the exact wrong day.

**Recommendation.** Treat this as a live production risk, not historical
evidence. Before anything else: implement the two prescribed fixes (fail-closed
`auto.offset.reset=none` + rebalance-time offset-range validation for the
verified consumer; persisted UUID binding for verified and output topics,
mirroring `CalcifySourceRegistration`), add the negative-path tests the
handoff's own "Immediate Next Actions" already specified, and add a process
gate (branch protection / required review checklist) that would have
actually stopped this merge — "the handoff doc said don't merge" is not a
technical control.

### Finding 2 (P1 — correctness). Matching-engine order-ID index is global, not run-scoped — inverts the premise the resolver is built on

**Claim.** The order book itself is correctly scoped by `(runId,
venueSessionId, instrumentId)` as D-042 and this PR intended. But the
separate order-ID→record index that backs duplicate detection, cancel, and
modify is keyed by order ID **alone**, globally across all runs. This means
the system does not actually guarantee what the Calcify architecture review
assumed when it designed the resolver's local index around the key
`(runId, orderId)`.

**Evidence.**

- `services/matching-engine/internal/app/book_scope.go:9-11` — the book map
  key is a genuine length-framed `(runId, venueSessionId, instrumentId)`
  composite, fixed in this PR from the pre-PR code (`git show
  56b882d4:.../service.go:794-821`, which joined only
  `venueSessionId + "|" + instrumentId`, no RunID at all). This part of
  D-042 is real and correctly implemented — confirmed by diffing against the
  pre-fix code and by `TestBookScopeKeysAreUnambiguous`.
- `services/matching-engine/internal/app/order_index.go:37-39,51-60` — the
  order index's `shard()` hashes **only** the order ID, and `reserve()`'s
  uniqueness check is `shard.orders[record.OrderID]`, with no `RunID`
  component. `RunID` is carried on the stored record but plays no role in
  the index key.
- `service.go:241-242` — `submitOrder` rejects with `DUPLICATE_ORDER_ID`
  engine-wide on an index collision, before any run check applies.
- `TestSubmitOrderRejectsDuplicateOrderIDWithoutMutatingBook`
  (`service_test.go:571-609`) proves this is deliberate, pre-existing
  behavior — submitting the same order ID on a different *instrument* (let
  alone a different run) is rejected today.
- No test in the repository submits the same order ID under two different
  `RunID`s. Every cross-run test (`run_scope_test.go`, both in `internal/app`
  and `internal/streamdirect`) uses distinct order IDs per run. The one
  scenario the architecture review explicitly calls out as expected
  behavior — reused order IDs across runs — has zero test coverage in either
  direction.
- This directly contradicts [docs/research/CALCIFY_PHASE2_ARCHITECTURE_REVIEW_2026-09-29.md](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/CALCIFY_PHASE2_ARCHITECTURE_REVIEW_2026-09-29.md)
  Piece 2: *"Order IDs are not assumed globally unique unless the contract
  proves that."* The contract proves the opposite of what was assumed: it
  enforces global uniqueness by rejection, not per-run uniqueness by
  isolation.
- A prior independent review of this exact PR
  (`docs/evidence/calcify-phase2-implementation/review-findings.md`)
  explicitly stated: *"No additional actionable code defect established in
  inspected Go run scope/snapshots."* This finding was missed by that
  review.

**Secondary gaps found in the same area:**

- **Cancel/Modify context bypass**: `matchesOrderContext` (`service.go:480-491`)
  returns `true` unconditionally when all five context fields (run, session,
  instrument, participant, account) are blank — a legacy-compat carve-out
  that also means any cancel/modify command that omits context fields
  bypasses run-ownership verification entirely and can act on any order by
  ID alone, regardless of its true run. No test combines this bypass with a
  genuinely mismatched run.
- **No route-vs-payload cross-check for RunID**: `internal/streamdirect/processor.go`
  overwrites `VenueSessionID`/`InstrumentID` from the Kafka route for
  Cancel/Modify (not Submit), but **never** cross-checks `RunID` against the
  route for any command type — it is trusted entirely from the payload. This
  is masked today because one process consumes all partitions into one
  shared `*app.Service`, but D-042's stated end state (partition-sharded
  processes) would make an intake-side hash mismatch capable of silently
  splitting one run's book across two independent service instances with no
  shared state. Flagged as a structural risk for the multi-process future,
  not a bug that fires today — the intake-side hashing code (Kotlin) was not
  reviewed as part of this pass.

**Why it matters.** The resolver's local accepted-order index
(`CalcifyResolverProcessor.orderKey`, `services/platform-runtime/.../CalcifyResolverProcessor.kt:178`)
and the whole Phase 2 architecture document assume `(runId, orderId)` is a
safe, collision-free local key. The matching engine does not actually
guarantee that assumption — it guarantees something weaker (global order-ID
uniqueness) that happens to prevent silent corruption today, but at the cost
of incorrectly rejecting legitimate orders the moment two concurrent runs
pick the same order ID, which is plausible for any client that generates IDs
per-run rather than globally.

**Recommendation.** Make the order-ID index run-scoped
(`(runId, orderId)` key, mirroring `book_scope.go`'s framing approach to
avoid separator-collision bugs), add the missing cross-run-reused-order-ID
test in both directions, close the cancel/modify context-bypass loophole (at
minimum: reject blank-context commands rather than treating them as
wildcard), and decide explicitly whether `RunID` needs route cross-
validation before any multi-process sharding is implemented.

#### Addendum, 2026-10-01 — proposed fix design

There are actually two legitimate directions here, and which one is right is
a product decision, not purely an engineering one — flagging the fork
explicitly rather than picking silently.

**Option A — make the engine match the documented assumption (run-scoped
order IDs).** This is the "correct" fix relative to what the Calcify
architecture review already assumed and designed around, and it's the
direction this design sketches out:

1. Add a `runScopedOrderKey(runId, orderId)` helper next to `bookKey` in
   `book_scope.go`, using the same length-framed encoding `bookKey` already
   uses — this is what makes `TestBookScopeKeysAreUnambiguous` pass today,
   and it's the right precedent to reuse rather than a plain string join.
2. Change `OrderIndex`'s stored map key from `OrderID` alone to that
   composite. The shard-selection hash (`shard(orderID)`, used purely to
   spread lock contention across shards) can keep hashing on `OrderID`
   alone — only the *stored* key inside a shard needs to become composite.
3. Legacy/blank-run compatibility falls out for free: today's non-run-aware
   callers already pass `runId=""`. Treating `""` as an ordinary key
   component (not a special case) means every existing non-run-scoped caller
   keeps colliding globally among themselves exactly as today — no
   behavior change for that population — while any caller that supplies a
   real `runId` gets genuine per-run isolation. No branching logic needed
   beyond using the composite key everywhere.
4. Thread `cmd.RunID` into every index call site alongside `OrderID`:
   `submitOrder`, `cancelOrder`, `modifyOrder`, `OrderState`, and their
   batch variants. `RunID` is already available at each of these call
   sites (it already flows to `bookFor`), so this is mechanical, not a new
   plumbing problem.
5. Close the `matchesOrderContext` all-blank bypass in the same change:
   require the bypass to be *symmetric* — succeed only when the request's
   context is blank **and** the stored order's own context is also fully
   blank — rather than treating a blank request as "match anything."
   Otherwise a scoped index plus an unscoped bypass still leaks: a
   blank-context cancel could still reach into a real run's order.
6. Verify whether `OrderIndex` entries are persisted directly in
   `Snapshot()`/`Restore()` or rebuilt from book contents on restore. If
   rebuilt (likely, given books are already the source of truth for
   resting orders), no new snapshot version is needed. If persisted
   directly, this is a breaking internal layout change requiring the same
   kind of version bump/migration precedent `service_snapshot.go` already
   has for V2→V3. This needs to be confirmed before implementation, not
   assumed either way.
7. New tests required: (a) two runs submit the same order ID and both
   remain independently resting/cancelable — proves real isolation; (b) the
   existing blank-run duplicate-rejection test
   (`TestSubmitOrderRejectsDuplicateOrderIDWithoutMutatingBook`) continues
   to pass unchanged, as a regression guard on legacy behavior; (c) a
   blank-context cancel cannot reach a run-scoped order post-fix; (d) an
   order ID reused by a new run after the original run's orders have fully
   terminated and been evicted does not leak stale state forward.
8. Given this touches shared matching-engine state handling (moderate blast
   radius, financial-correctness-relevant), this should land as its own
   reviewed PR under `go test -race ./...` plus the existing
   `run_scope_test.go` suite, ahead of any Phase 3 work — consistent with
   Finding 9's sequencing recommendation.

**Option B — accept global order-ID uniqueness as the actual contract, and
fix the assumption instead of the engine.** Smaller blast radius: update
`docs/DECISIONS.md`/the Calcify contract docs to state that order IDs must
be globally unique (push that requirement onto every client/bot that
generates them), and treat the resolver's `(runId, orderId)` index key as
already-safe-in-practice specifically *because* of that global constraint
(it would then be using `runId` as a harmless extra discriminator, not a
required one). No matching-engine change needed. Downside: every current
and future client that generates order IDs per-run (which is the more
natural pattern for most clients, including the simulator/bot SDK, which
would need an audit) has to change, and this bakes a known footgun
("silently reject the second run's order" is a worse failure mode than "my
IDs aren't globally unique") into the product permanently rather than
fixing it once in one place.

**Recommendation between the two:** Option A, because it fixes the
footgun at its source and matches what was already designed and documented
elsewhere, and the engine-side change is bounded and mechanical once the
snapshot question (step 6) is answered. But this is the kind of call that
should be made deliberately rather than inherited from whichever fix is
easier — see the question raised back to the user alongside this review.

#### Implemented, 2026-10-01 — Option A landed

The user chose Option A and asked for it to be implemented immediately.
Done, split into its own PR for focused review:
[`claude/calcify-run-scoped-order-index`](https://github.com/dills122/reef/pull/436).
`go build ./...`, `go vet ./...`, and `go test -race ./...` all pass across
the whole `services/matching-engine` module. The CI gap from Finding 3 is
likewise split out into its own PR:
[`claude/calcify-tests-ci`](https://github.com/dills122/reef/pull/435).

**What actually changed**, beyond the sketch above — tracing the real call
graph surfaced three things the sketch didn't anticipate:

1. The order index (`order_index.go`), `orderBook` (now carries its own
   `RunID`, set once at creation), and every call site in `service.go` that
   looked up an order by ID alone now key on `(RunID, OrderID)` via a new
   `orderIndexKey` helper in `book_scope.go` (same length-framing discipline
   `bookKey` already uses, so a run ID or order ID containing a separator
   can't forge a collision).
2. **The terminal-order-retention tracker (`terminal_retention.go`) had the
   identical bug**, one level removed: its eviction heap was also keyed on
   raw `OrderID` alone, with no `RunID`. Once order IDs can legitimately
   repeat across runs, evicting "the oldest terminal order with this ID"
   without a run qualifier would evict the wrong run's order. Fixed the
   same way — the heap entry, the `track`/`commit` eviction callback, and
   `BatchRollback`'s deferred `terminalOrderIDs` (now `[]orderIdentity`
   instead of `[]string`) all carry `RunID` through to the eviction point.
3. **Snapshot validation had the same bug in three places**, because it
   also used `OrderID` as a map key: the `Restore()` duplicate-order-ID
   guard, `validSnapshotOrderScopes`'s book-membership cross-check, and the
   deterministic sort order feeding `serviceSnapshotChecksum`. All three
   now key on `(RunID, OrderID)`. The checksum sort's tie-break is additive
   only — it produces byte-identical output to before for any snapshot
   where every order ID was already unique, which was every snapshot the
   old, globally-unique index could ever have produced; it only changes
   behavior for the new case (two runs, same order ID) that was previously
   impossible to construct.

`BatchRollback.hasInstrumentOrder` and the cross-book `rb.records` map
(used for self-trade-prevention bookkeeping outside the current
instrument's book) are now also run-qualified, closing a latent version of
the same bug that would otherwise have let one run's tracked order
shadow-block tracking of a different run's order with the same ID within
one batch.

**`Service.OrderState(orderID string)` → `OrderState(runID string, orderID
string)`.** This method has zero production callers anywhere in the repo
(confirmed by grep — not called from the gRPC or HTTP transport layers,
only from its own test files), so this is a safe, mechanical signature
change rather than a breaking API decision. All ~40 call sites across
`service_test.go`, `service_fuzz_test.go`, `run_scope_test.go`, and three
`internal/streamdirect` test files were updated.

**One real, intentional behavior change, not just a test fixup**: a
`CancelOrder`/`ModifyOrder` that names the *wrong* run for an order that
really belongs to a different run now rejects with `NOT_FOUND` instead of
`ORDER_CONTEXT_MISMATCH` — because the lookup itself is scoped by the
caller's claimed run, so a wrong run ID can no longer find the record at
all (previously: global lookup found it, then `matchesOrderContext`
rejected the mismatch). This is arguably more correct — it means Reef no
longer distinguishes "this order exists in a run you're not in" from
"this order doesn't exist," which avoids leaking cross-run existence — but
it's a real, observable change to a rejection code returned to real
callers through the production `streamdirect` Kafka path (gRPC's
non-batch path is a separate, already-flagged scaffold). Updated and
documented in `TestLifecycleMutationsRejectClaimsThatDoNotMatchTargetOrder`
(`service_test.go`); the sibling case where the run is right but another
field is wrong still correctly returns `ORDER_CONTEXT_MISMATCH`, unchanged.
The all-blank-context legacy bypass in `matchesOrderContext` needed no
code change at all: scoping the lookup by the caller's own claimed
(possibly blank) run subsumes it — a blank-context caller can no longer
reach a real run's order by construction, without touching that function.

**New tests added** (`run_scope_test.go`):
`TestReusedOrderIDAcrossRunsStaysIsolated` proves two runs may reuse the
same order ID, each is independently readable/cancelable, and a
blank-context cancel reaches neither.
`TestSnapshotRestoresReusedOrderIDAcrossRuns` proves the snapshot path
(dedup, scope validation, checksum) round-trips that same scenario rather
than rejecting it as corrupt. Existing tests
(`TestSubmitOrderRejectsDuplicateOrderIDWithoutMutatingBook`,
`TestRunBooksIsolateMatchingAndLifecycle`, and the rest of the pre-existing
`run_scope_test.go`/`service_test.go` suites) continue to pass unchanged
except where noted above.

**Not done, and still open:** the snapshot-persistence verification from
design step 6 (confirmed moot — `service_snapshot.go` rebuilds the order
index from `snapshot.Orders` on `Restore`, it was never a second
persisted copy, so no version bump was needed) and the route-vs-payload
`RunID` cross-check flagged as a future-multi-process risk in the original
finding — intentionally out of scope for this fix, since it's not reachable
under the engine's current single-process-per-set-of-partitions topology.

### Finding 3 (P1 — process/test). CI never exercises the tests that would catch any of the above

**Claim.** None of the Calcify test suites that touch a real broker or a
real Postgres instance actually run in CI. The only tests that run
automatically are pure in-memory/mocked tests.

**Evidence.**

- `CalcifyReceiptStoreTest.kt` — both test methods begin with
  `val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return`,
  i.e. the test silently passes with zero assertions if the env var is
  unset.
- `.github/workflows/ci.yml`'s `kotlin-platform-runtime` job (lines
  178-196) runs `./gradlew test` but sets no `RUNTIME_POSTGRES_*` env var
  and starts no Postgres service. The only workflow job that sets
  `RUNTIME_POSTGRES_JDBC_URL_TEST` (`postgres-schema-placement`, lines
  608-620) runs an unrelated test class in a different module entirely.
- Grepping every `.github/workflows/*.yml` for the literal string
  `"calcify"` returns zero matches.
- `CalcifyResolverBrokerProbe.kt` — a real Kafka-backed harness used to
  produce every piece of load/fault evidence in the implementation docs —
  has no `@Test` annotation and is invoked only via manual `make
  dev-smoke-calcify-*`/`dev-soak-calcify-*` targets, none of which are
  referenced from any GitHub workflow.
- `CalcifyResolverProcessorTest.kt` uses Kafka Streams' in-process
  `TopologyTestDriver`, which never opens a socket and cannot exercise
  partition reassignment, broker-side transaction abort, or network-level
  duplicate delivery.
- Phase 1's extractor/verifier/receipt stage orchestration
  (`CalcifyPipeline.run()`, the code that actually owns
  `KafkaConsumer`/`KafkaProducer`/transactions) has no test that calls it
  directly at all — `CalcifyVerifierBatchTest.kt`/`CalcifyReceiptBatchTest.kt`
  call the pure batch-planning functions with hand-built `ConsumerRecord`
  objects, bypassing the actual runtime loop.

**Why it matters.** `docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md:24`'s claim
that "focused Gradle tests pass" is true only of the in-memory unit tests.
Every piece of real-infrastructure evidence in this project — the sustained
load numbers, the fault-injection results, the recovery timings — was
produced by hand, locally, and is not reproduced automatically by anything
that gates a merge. This is the mechanism by which Finding 1's two P1s
shipped undetected: nothing in CI could have caught them even if someone
had written the prescribed negative tests, because the job that would run
them isn't configured to exercise real infrastructure.

**Recommendation.** Stand up a CI job (even a slow, nightly one, separate
from the per-PR gate) that runs `CalcifyReceiptStoreTest` against a real
Postgres service container and runs at least the fault-boundary subset of
`CalcifyResolverBrokerProbe` against a real Redpanda container. Until that
exists, "tests pass" cannot be used as evidence for this subsystem's
correctness claims.

### Finding 4 (P1 — capacity). The 10,000 resolved-commitments/s target has never been met under the project's own frozen gate set

**Claim.** Every sustained (300-second-class) Phase 2 capacity run has
failed at least one of its own pre-declared gates. The project's evidence
docs say this plainly; it is worth restating because the headline numbers
("10,160.50/s") read like a pass in isolation.

**Evidence.**

- `docs/evidence/calcify-phase2-implementation/review-findings.md`:
  *"All sustained attempts fail at least one frozen gate; final
  fixed-candidate repeats required."*
- Best attempt (`sustained-8ea6c8ce`, `docs/THROUGHPUT_BASELINES.md:1454`):
  10,160.50/s, end-gap and covering-gap both pass — but *"Actual producer
  duration 310.730s fails 301s maximum, so cohort and four-profile
  qualification fail."*
- Earlier attempts failed on different axes: `sustained-53a64664`
  (10,605.80/s but 127,510 end-gap vs. 20,000 max); `sustained-fa7fec51`
  (9,881.14/s — below the rate floor — plus both backlog gates failed);
  `sustained-1fadb3e2` (Docker VM filled, all three brokers exited 133
  before producing a rate at all).
- Required workload coverage per Piece 6 of the architecture review (hot,
  spread, skew, aged-state lane profiles) has never gone past "hot" — every
  attempt stopped at the first failing gate before the other three profiles
  ran.
- Zero hosted or multi-node evidence exists for Calcify at all, Phase 1 or
  Phase 2. Every run — including the "RF3" fault-matrix runs — is 1-3
  containers on a single Docker host (`docs/work/handoffs/2026-09-30-calcify-phase2.md:66`:
  "3×1CPU/1GiB Redpanda... 2GiB container limits" — three containers, one
  machine). The only genuinely hosted/multi-node numbers in the whole
  repository (C5/C2) are the pre-Calcify legacy baseline, and the project's
  own docs say explicitly these "cannot be promoted as Phase 2 results."
- No run has ever injected a broker or node failure while sustained load was
  actually flowing. Fault-injection evidence exists only at toy scale
  (130-520 records) or against an idle, cold-restarted system (1M-row
  recovery test used only a 100-trade catch-up workload after restart, not
  resumed high-rate traffic).
- Real HTTP-intake-fed throughput (as opposed to synthetic Go-fixture-
  injected source batches used for the headline capacity numbers) tops out
  around 5,000 orders/s clean (`calcify-phase1-go-5k-upper-5m.json`,
  reconciled, zero drops) — pushing to 7,500/s drops ~14% of offered load.
  The architecture review's own math (`CALCIFY_PHASE2_ARCHITECTURE_REVIEW_2026-09-29.md:77`)
  says sustaining 10,000 resolved trades/s needs roughly 20,000 successful
  order commands/s upstream — a ~4x gap between what real intake has ever
  sustained and what Phase 2 qualification needs to be fed.

**Why it matters.** "10,000/s" has been used informally as a milestone this
project is near. The honest state is: no conforming sustained run exists at
any workload profile; the only near-miss failed on a 3% duration overshoot
after two prior attempts failed on backlog gates; the fault-tolerance half
of the capacity question has literally never been attempted under load; and
the realistic input pipeline (real order intake, not synthetic fixture
injection) is currently ~4x short of what the target needs. This is squarely
still in the diagnostic phase, not the qualification phase, despite two full
implementation phases of engineering investment.

**Recommendation.** Do not describe Phase 2 as "near 10k/s" in planning
conversations without the duration/workload/fault-tolerance caveats above.
Before any further Phase 2 feature work, run one clean, frozen-candidate,
all-four-profile attempt end to end (the handoff's own "Immediate Next
Actions" step 5 already specifies this) and treat anything short of that as
the actual current capacity, not the best individual-run number.

### Finding 5 (P2 — correctness, inherited). Phase 1 has the same retention-loss exposure Phase 2 was blocked on — just never gated on fixing it

**Claim.** The exact silent-data-loss shape behind Finding 1's P1-A exists
unguarded in Phase 1's extractor/verifier/receipt consumers too. It predates
Phase 2 and was explicitly left as a documented TODO rather than a blocking
defect, which is a defensible call for an "additive opt-in diagnostic slice"
— but it means the gap is wider than just the Phase 2 resolver.

**Evidence.**

- `CalcifyPipeline.kt:37-39,249` — the extractor/verifier/receipt consumer's
  `auto.offset.reset` defaults to `"earliest"` with no equivalent of
  `BrokerVenueSourceReader`'s `beginningOffsets()` retention check anywhere
  in the Phase 1 stage loop.
- Phase 1's own internal topics (`REEF_MATCH_COMMITMENTS_V1`,
  `REEF_VERIFIED_COMMITMENTS_V1`) are created with **replication factor 1
  and no retention/cleanup config at all** (`CalcifyPipeline.kt:228`: bare
  `NewTopic(topic, count, 1.toShort())`), unlike the resolver's much more
  defensive `ResolverTopicDurability`-gated topic creation. For a pipeline
  feeding a financial audit trail, RF1 with no explicit retention means a
  single broker loss between verifier output and receipt commit can lose a
  verified link outright, indistinguishable from "verifier hasn't produced
  it yet."
- `docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md:327` already names this as
  open follow-up: *"Before non-diagnostic no-archive run, enforce maximum
  run duration and post-close replay window... This is focused follow-up."*
  That follow-up has not happened.
- No Phase 1 stage exposes any health/readiness/metrics endpoint (unlike the
  resolver's `/healthz`/`/readyz`/`/metrics`) — a stuck or poisoned partition
  is visible only via `System.err.println` log lines, so even when the
  fail-closed paths that do exist (e.g. receipt policy-version conflicts,
  which are genuinely well-handled — see below) trigger correctly, nothing
  surfaces it operationally beyond log scraping.

**One genuinely solid piece found in the same area**: Phase 1's receipt
idempotency (`CalcifyReceiptStore.kt`) is rigorous — `INSERT ... ON CONFLICT
DO NOTHING`, explicit re-read and policy-version-match assertion on
conflict, whole-batch rollback on any mismatch, then correct per-record
replay to isolate exactly the conflicting offset while letting the rest of
the partition continue. This is a good pattern and noticeably more
defensive than the equivalent surface in Phase 2. One inconsistency: an
unregistered/garbage `source_generation` violates a foreign key and throws
`SQLException`, which is *not* caught by the same narrow
`IllegalArgumentException` handler that isolates poison records per-
partition — it instead crashes the whole receipt-worker process. Still
fail-closed, just at a coarser granularity than the rest of the poison-record
handling.

**Recommendation.** Decide explicitly whether Phase 1's no-archive retention
gap is acceptable for its current "additive opt-in diagnostic" status (it
may well be) and document that decision, rather than letting it sit as an
ambient TODO indefinitely while Phase 2 gets built on top of it. At minimum,
give Phase 1's internal topics real retention/replication settings before
any non-diagnostic use, and fix the exception-type inconsistency in the
receipt worker's poison handling.

#### Addendum, 2026-10-01 — expanded mechanics and remediation design

**Why this is silent, precisely.** Kafka/Redpanda consumers don't raise an
error when a committed offset ages out of retention under
`auto.offset.reset=earliest` — the client library treats it as a normal,
expected condition and transparently reseeks to whatever the new oldest
retained offset is. There is no exception thrown, no log line emitted by the
broker client by default, nothing for `CalcifyPipeline`'s existing
poison-partition pause logic to catch, because nothing went wrong from the
*consumer's* point of view — it just quietly starts reading later data than
it left off at. The failure is entirely in the gap between "what the
consumer thinks happened" (normal continuation) and "what actually happened"
(an unbounded range of source records between the old checkpoint and the new
oldest-retained offset was never processed).

**Blast radius of that gap, traced through the pipeline.** If the extractor
is the stage that resets, every `TradeCreated` in the skipped range never
gets a `MatchCommitment` — those trades simply never enter Calcify at all,
with no record that they existed. If the verifier resets, already-extracted
commitments in the skipped range never get verified or receipted — they
exist in the commitment log forever but are permanently invisible downstream.
If the receipt worker resets, already-verified links never get a receipt
row — the audit trail silently has holes exactly where the system was
under the most backlog pressure, which is also the scenario most likely to
cause this in the first place (a lagging stage is a stage close to its
retention boundary). In every case, the gap is invisible unless someone runs
an independent offset-by-offset reconciliation against the source, which
this review found no evidence of ever being run for Phase 1 (independent
source-to-commitment reconciliation is listed as explicitly out-of-path,
future work, in `CALCIFY_DISCOVERY.md`).

**Concrete remediation, scoped to avoid duplicating Finding 1's fix twice.**
Rather than writing two independent fail-closed guards (one for the
resolver's verified consumer per Finding 1, one for Phase 1's three
consumers here), extract one reusable component — e.g. a
`CalcifyOffsetGuard` used by every Calcify consumer (extractor, verifier,
receipt, and the resolver's verified-topic consumer alike):

1. `auto.offset.reset=none` everywhere Calcify currently defaults to
   `earliest`, so an out-of-range offset throws instead of silently
   resetting.
2. On every partition assignment (`ConsumerRebalanceListener.onPartitionsAssigned`,
   which `CalcifyPipeline`'s extractor already uses for generation
   re-verification — this is the right place to extend, not a new
   mechanism), call `beginningOffsets()`/`endOffsets()`/`committed()` for
   the assigned partitions with a bounded timeout, and require
   `beginning <= checkpoint <= end`. A missing checkpoint (brand-new
   consumer group) is the only case allowed to seek to `beginning` — a
   stale checkpoint that has fallen below `beginning` must fail the
   process, not silently advance.
3. Give Phase 1's internal topics (`REEF_MATCH_COMMITMENTS_V1`,
   `REEF_VERIFIED_COMMITMENTS_V1`) explicit `retention.ms` and real
   replication (not the current hardcoded RF1), sized per the discovery
   doc's already-agreed "Agreed retention constraint"
   (`CALCIFY_DISCOVERY.md:69-71`): broker retention must cover the oldest
   source fact through maximum run duration, replay window, processing
   lag, and a safety margin — this is already-approved design, just not
   yet implemented.
4. Add the negative test this enables for all four consumers in one pattern:
   truncate the topic past a stage's checkpoint, restart, assert a nonzero
   exit and zero silent skip, exactly mirroring the test `review-findings.md`
   already prescribed for the resolver.
5. Give the extractor/verifier/receipt stages the same `/healthz`/`/readyz`/
   `/metrics` surface the resolver already has, so a paused/faulted
   partition is discoverable without log scraping — this is a small,
   mechanical addition (the resolver's `HttpServer`-based implementation in
   `CalcifyResolverRuntime.kt:70-84` is a direct template) and it closes
   the "only visible via `System.err.println`" gap called out in the
   original finding.

Building this once as a shared component rather than twice (Phase 1 and
Phase 2 independently) is also the right call structurally — see the
architecture sketch addressing Finding 7 for where this fits into a
consolidated design.

### Finding 6 (P2 — reliability). Pre-publish, non-durable book state is readable by concurrent clients before a batch's rollback decision is known

**Claim.** The matching engine's batch rollback mechanics are correct for
every case they're tested against, but book/order mutations are applied to
the live, shared, lock-released state *before* the batch's Kafka publish
outcome (and therefore its commit/rollback decision) is known — and that
window is externally observable.

**Evidence.**

- `internal/streamdirect/processor.go` (`buildBatchMode`) snapshots state via
  `BeginBatch`, then applies every command in the batch synchronously to the
  live `s.books`/`s.orderIndex` before attempting `PublishEventBatch`. Each
  individual command takes its book's lock only for its own duration
  (`service.go:223,291,358`) — not for the whole batch — and releases it
  immediately, well before the batch's publish round-trip to Kafka/Redpanda
  completes.
- `transport/http/server.go:86` exposes `BookStats` (buy/sell counts, price
  levels, checksum) over `GET`, reading through the same per-book lock
  `OrderState`/`Snapshot` use — which means it happily returns tentative,
  not-yet-durable state mid-batch, with no signal to the caller that the
  result might be rolled back moments later.
- The multi-command rollback tests that exist
  (`TestPublishFailureRollbackRestoresMultiCommandBatch`,
  `TestPublishFailureRollbackRestoresPassiveMatchedLiquidity`,
  `TestRunBatchFailedPublishRestoresSequenceAndAcceptance`) all run single-
  threaded and assert state only *after* rollback completes — none exercises
  a concurrent reader observing state *during* the window.
- Separately, and orthogonally: a non-batch gRPC write path
  (`internal/transport/grpc/server.go:73-102`, gated behind
  `MATCHING_ENGINE_ENABLE_GRPC=1`) calls the non-batch `SubmitOrder`/
  `CancelOrder`/`ModifyOrder` variants directly — no `BeginBatch`, no
  rollback protection, and apparently no corresponding `VenueEventBatch`
  publication. If ever enabled in a real deployment alongside the durable
  Kafka path, orders submitted this way would mutate live book state with
  zero durability and vanish silently on a crash, breaking the "all matches
  are backed by a durable venue-event batch" invariant Calcify's entire
  design depends on.

**Why it matters.** This is a correctness/visibility gap, not a data race
(reads do take the lock) — but any client polling book state during normal
operation can observe provisional results from a batch that later rolls
back, with no indication that what it read was not durable. This becomes
materially worse if the gRPC scaffold is ever enabled outside dev/test,
since it would produce book mutations with no durability story at all.

**Recommendation.** Confirm `MATCHING_ENGINE_ENABLE_GRPC` is never set
outside local dev/test, and consider either removing the scaffold or wiring
it through the same batch/publish/rollback path as the Kafka route before
it can be enabled anywhere real. For the read-visibility gap, consider
exposing a "durable-as-of" marker on `BookStats`/`OrderState` reads so
callers can distinguish committed from provisional state, if this endpoint
is ever relied on for anything beyond local diagnostics.

### Finding 7 (P2 — contract design). Phase 1's wire format sits outside the project's own additive-only enforcement; commitment identity is purely positional

**Claim.** The project has a real, CI-enforced additive-only schema gate —
but it only covers the Phase 2 protobuf messages. Phase 1's `MatchCommitment`/
`CommitmentVerificationPassed` records are hand-rolled fixed-width binary,
entirely outside that gate, protected only by documentation and a version
byte. Separately, commitment identity carries no content hash, so it cannot
detect in-place content divergence at a previously-read offset.

**Evidence.**

- `scripts/check-proto-additive.sh` + `services/matching-engine/cmd/proto-compat-check/main.go`
  is a real, non-trivial descriptor-diff gate (catches removed
  fields/messages, renumbering, retyping, enum-value changes) and is wired
  into CI (`.github/workflows/ci.yml:82-111`) — this part of the contract
  discipline is genuinely solid.
- `MatchCommitment`/`CommitmentVerificationPassed` are not protobuf at all —
  they're defined in `CalcifyContract.kt:26-64` as 21-byte/23-byte
  big-endian records, consistent with `contracts/calcify/README.md`. They
  are entirely outside `check-proto-additive.sh`'s scope: no descriptor
  diff, no CI gate, nothing machine-checked preventing a future change from
  reshuffling the byte layout under an unchanged version tag. The only
  protection is the `CalcifyContractTest.kt` unit test and a runtime check
  of the leading version byte (which guards cross-version mixing, not
  intra-version layout drift).
- Commitment identity is `(sourceGeneration, sourcePartition, sourceOffset,
  tradeOrdinal)` with, by explicit design, *"No trade economics, trade ID,
  batch ID, checksum, or duplicate ID field... stored in link"*
  (`contracts/calcify/README.md:3`). The only integrity check
  (`CalcifySourceBatch.checked()`) validates a batch's checksum against
  *itself*, computed from whatever bytes are currently at that offset when
  read — not against a hash captured when the commitment was first minted.
  A full topic recreation is caught (bound topic UUID); compaction is
  rejected (`cleanup.policy != compact` enforced); but in-place content
  divergence at the same offset of a never-recreated topic — e.g. an
  unclean leader election during a broker failover replacing a partition
  leader with an out-of-sync follower — has no detection anywhere in the
  Calcify code or docs. No `unclean.leader.election.enable` setting was
  found for these topics.
- Minor hygiene gap: `contracts/proto/calcify.proto`'s `go_package` option
  is copy-pasted from `order_execution.proto` verbatim — confirmed in
  generated output, `calcify.pb.go` declares `package orderv1`, the same Go
  package as `order_execution.pb.go`. Not causing a collision today, but
  gives Calcify's Go messages no package identity of their own.

**Why it matters.** The additive-only gate is good work and should be kept —
but its coverage gap means half of Phase 1's wire contract (the half that's
actually load-bearing for commitment identity) has no structural protection
against layout drift beyond a version byte and a unit test. The missing
content-hash-at-mint-time is a narrower, lower-probability gap (requires an
unclean leader election specifically, not just any broker restart) but is
worth a deliberate accept-or-fix decision rather than silence.

**Recommendation.** Either bring the Phase 1 binary records under the same
descriptor-diff discipline (e.g. define them in `.proto` even if encoded
compactly, or write a dedicated layout-diff script mirroring
`check-proto-additive.sh`'s approach for fixed-width records), or
explicitly document why a version byte plus unit test is considered
sufficient. Fix the `go_package` copy-paste. Decide and document whether
unclean-leader-election-induced content divergence is in scope for Calcify's
threat model; if yes, it needs a content hash captured at mint time, not
just a self-consistency check at read time.

#### Addendum, 2026-10-01 — expanded risk assessment and lower-cost options

**The unclean-leader-election risk is real but probably narrower than it
first looks, and that changes what the right fix is.** Reef's own prior
research already drew the relevant distinction
(`CALCIFY_PHASE2_IMPLEMENTATION.md:30`): *"Redpanda does not implement Kafka
ISR durability semantics"* — Redpanda replicates via Raft, and Raft's
leader-election protocol requires a candidate's log to be at least as
up-to-date as a majority of the cluster before it can be elected leader at
all. There is no Raft equivalent of Kafka's `unclean.leader.election.enable`
setting, because Raft's correctness proof depends on that property — an
out-of-sync replica cannot become leader and silently serve different
content at an already-acknowledged offset. So on the project's primary
target backend (Redpanda, `CALCIFY_RESOLVER_BROKER_KIND=REDPANDA` is the
default in `compose.calcify.yml`), this specific failure mode is likely
already structurally prevented by the replication protocol itself, not by
anything Calcify's own code does.

The residual risk is narrower and backend-specific: the resolver explicitly
supports running against real Kafka too
(`CALCIFY_RESOLVER_BROKER_KIND=KAFKA`, `ResolverTopicDurability.kt`), and
real Kafka *does* have `unclean.leader.election.enable`, which defaults to
`false` in modern Kafka but can be set `true` by an operator (e.g. to
prioritize availability over consistency during a multi-broker outage).
Nothing in `ResolverTopicDurability.validate`/`validateAcknowledgement`
currently asserts this is `false` for Calcify's own topics when running
against the Kafka backend — it checks `min.insync.replicas` and
`write.caching`, not this setting.

**Given that, the cost/benefit flips toward a cheap guard rather than a
wire-format change.** A content hash captured at mint time would close the
gap completely regardless of backend or config, but it is a breaking wire
format change under Phase 1's own stated versioning rule (*"Wire version or
field-layout changes require new contract version and stream,"*
`contracts/calcify/README.md:12`) — a new topic, new consumers, a cutover.
That is a disproportionate fix for a risk that (a) doesn't apply to the
default/primary backend at all, and (b) on the backend where it could apply,
is only reachable via an explicit, non-default operator misconfiguration.
The proportionate fix is to add that one assertion —
`unclean.leader.election.enable=false` required on Calcify's own topics when
`CALCIFY_RESOLVER_BROKER_KIND=KAFKA` — to the existing startup validation in
`ResolverTopicDurability`, right alongside the existing `min.insync.replicas`
check. This closes the actual reachable risk with a few lines, no wire
change, no cutover. Reserve the content-hash-at-mint approach for if/when
the project decides it needs audit-grade proof independent of broker
configuration (e.g. for a regulated settlement boundary later) rather than
building it now for a risk that's mostly already closed by the choice of
Redpanda.

**Lower-cost alternative for the additive-discipline gap (Finding 6's other
half).** Standing up a full descriptor-diff tool for a fixed 21/23-byte
record is more machinery than the problem needs. A cheaper, still-real
guard: check in a golden fixture file of exact hex-encoded bytes for a
representative zero-, one-, and many-trade `MatchCommitment` /
`CommitmentVerificationPassed` pair, and assert in `CalcifyContractTest`
that the current encoder reproduces those exact bytes, not just that it
round-trips through its own decoder. That catches accidental layout drift
(field reordering, width changes) the same way a descriptor diff would,
without needing new tooling — it's the same idea as `check-proto-additive.sh`
applied by hand to a format too small to need the general-purpose version.

### Finding 8 (P3 — engineering quality, for balance). What's actually well-built

To avoid this review reading as uniformly negative: several pieces are
genuinely solid and should not be churned in the course of fixing the above.

- The resolver's Kafka Streams `EXACTLY_ONCE_V2` transaction boundary
  (state write + output forward + input offset commit, all in one producer
  transaction per 100ms commit interval) is correctly built and is the one
  area backed by real crash-injection tests (`CalcifyResolverBrokerProbe.kt`
  modes `crash="state"/"commit"/"forward"`, using `Runtime.halt(91)` mid-
  processing). No duplicate or dropped output was found across any of those
  injection points.
- Phase 1's receipt-store idempotency (Finding 5) is a good pattern:
  conflict-aware batch insert with correct fallback to per-record isolation.
- The V2→V3 snapshot migration in the matching engine
  (`service_snapshot.go`) is sound and fail-closed for the real migration
  path — it correctly rejects a run-scoped snapshot smuggled in under a
  legacy version tag, and its checksum algorithm is unchanged from pre-PR
  code, so historical snapshots verify correctly. (Caveat: this mechanism is
  currently unused by production recovery, which replays the full command
  log instead — see below.)
- The project's own evidence documents are unusually honest: every failed
  run is preserved and labeled as failed rather than quietly dropped, and
  the "Qualification remains open" framing in `review-findings.md` is a
  genuinely rare level of self-reported candor for a nightly checkpoint.
  Keep this norm — it is what made this review possible to do precisely,
  rather than from secondhand claims.

One additional gap worth noting while on the subject of snapshots: D-042
describes snapshots as "recovery accelerators," but current production
recovery (`internal/streamdirect/runner.go:228`, `RestoreCommitted`) does a
full command-log replay and does not use snapshot files at all —
`WriteSnapshotFile`/`ReadSnapshotFile` are referenced only from test code.
The carefully-built V2→V3 migration logic is correct but currently dead code
from production's point of view.

### Finding 9 (P1 — strategic sequencing). Two implementation phases of real infrastructure have been built while all ten Calcify design decisions remain open

**Claim.** `docs/work/CALCIFY_DISCOVERY.md`'s decision register (CAL-01
through CAL-10 — product model, matching-boundary scope, canonical
inputs, scarce-account arbitration, financial finality, read contracts,
recovery/trust, physical authority, capacity envelope, cutover) is fully
open. None of the ten appears in `docs/DECISIONS.md`. Yet Phase 1 and Phase
2 have already built a durable commitment log, a stub verifier, a
PostgreSQL receipt placeholder, and a full Kafka-Streams/RocksDB resolver
with exactly-once semantics — substantial infrastructure — before CAL-05
(financial finality) or CAL-01 (product/obligation semantics) has been
decided at all.

**Evidence.** Cross-referenced `docs/work/CALCIFY_DISCOVERY.md`'s decision
table against `docs/DECISIONS.md` (grep for "CAL-0" returns zero matches in
the latter). The discovery doc's own delivery rule anticipates this
sequencing deliberately — *"Build one small capability at a time... A slice
must be correct for the behavior it claims; it does not need the later
product model... before the next slice can begin"* — so this is not an
accident; it is the chosen strategy of building infrastructure ahead of
product decisions.

**Why it matters, independent of whether the strategy was a deliberate
choice.** The thing Calcify ultimately exists to produce — a trustworthy
account of what happened, finalized and connected to settlement — has no
consumer yet: there's no financial finality model, no read API, no
settlement/ledger logic. Two phases of genuinely difficult infrastructure
(exactly-once Kafka Streams resolver, RocksDB-backed local index, run-scoped
matching) have been built to feed a pipe whose downstream end does not
exist, and that downstream end's shape (CAL-01, CAL-05) could still change
the upstream contract (`MatchContextResolvedV1`) in ways that invalidate
work already done. This is a legitimate, deliberate incremental-delivery
bet — but it is a bet, and it should be made with eyes open rather than
discovered three findings deep into a review. Given this review surfaced
real correctness gaps in the infrastructure itself (Findings 1, 2), there is
a real risk of sunk-cost pressure to keep building Phase 3+ on top of an
unvalidated foundation rather than stopping to fix Findings 1-2 and close at
least CAL-05 first.

**Recommendation.** Before starting any Phase 3 slice, explicitly revisit
whether the "build small capabilities first, decide product model later"
bet is still the right call given what this review found — specifically,
whether it's worth closing CAL-05 (financial finality) and at least a draft
of CAL-06 (read contracts) now, so the next infrastructure slice has a real
consumer to validate against instead of extending Phase 2's as-yet-unused
output.

## Consolidated recommendation, in order

1. **Do not build Phase 3 on this foundation yet.** Fix Finding 1 (the two
   P1 blockers, for real this time, with the negative tests the handoff
   already specified) and Finding 2 (run-scope the order-ID index) first.
   Both are small, bounded fixes relative to the infrastructure already
   built.
2. Close Finding 3 (real-infrastructure CI) before trusting any future
   "tests pass" claim for this subsystem.
3. Run one clean, all-four-profile capacity attempt (Finding 4) and use
   *that* number, not the best individual run, as the current capacity
   baseline for planning.
4. Make an explicit, documented call on Finding 5 (Phase 1 retention) and
   Finding 7 (contract-layout gate coverage, content-hash threat model)
   rather than leaving them as ambient TODOs.
5. Revisit Finding 9's sequencing bet with this review's findings in hand —
   decide whether to close CAL-05/CAL-06 before the next infrastructure
   slice.
6. Preserve what's working (Finding 8): the EOS transaction boundary, the
   receipt idempotency pattern, and the project's evidence-logging honesty
   are all worth keeping exactly as they are.

## Review methodology

Four parallel deep-read passes, each with direct file:line citations,
covering: (a) the Go matching engine's D-042 run-scoping, order-ID
indexing, snapshot migration, and batch rollback; (b) the Kotlin/Kafka-
Streams Phase 2 resolver, cross-checked against the two documented P1s by
direct code reading before delegating for a second independent pass; (c)
the Phase 1 extractor/verifier/receipt pipeline and its test/CI coverage;
(d) the wire/proto contracts, versioning discipline, and the full capacity
evidence trail under `docs/evidence/`. Findings 1's P1 confirmations were
independently verified firsthand (not merely taken on an agent's word)
before being included. All file:line references were current as of
worktree HEAD `1e77575a` on 2026-10-01.
