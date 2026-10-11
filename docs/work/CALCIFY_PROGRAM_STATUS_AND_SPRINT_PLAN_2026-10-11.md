# Calcify program status and three-sprint implementation plan — October 11, 2026

Status: **planning document; proposes no authority, makes no capacity claim, changes no contract.** It is the single place that
states where Calcify stands, whether the path is sound, and what to build next. Execution status stays on the
[work board](../WORK_PLAN.md#calcify-program--current-state-and-next-actions-october-11-2026); measurements stay in the
[throughput ledger](../THROUGHPUT_BASELINES.md); the architecture stays in the [RFC](CALCIFY_SYSTEM_ARCHITECTURE_RFC.md) (proposed).
Where this document and a dated evidence record disagree, the evidence record wins for its stated scope.
Baseline: `master` after PR485 (O1), PR488 (O2), PR489 (O3 packet, role definition), PR490 (research) and PR491 (O3a module).

## 1. Verdict in four lines

1. The correctness path (O1, O2 merged after independent review) is sound and repeatable; keep fresh-context review with a 3-instance cap.
2. **No research blocks implementation.** Every implementation unit below is decided and specified; the three open research items gate
   *claims and hardware sizing*, not code (section 4).
3. The 10k goal (orders through the API, booked, settlement lags) is not yet moved by the finite ladder; it is moved by the capacity track
   (keyed state, recovery). Both tracks run in parallel lanes with disjoint files.
4. Evidence debt (inherited O2 live evidence, unmerged Records PRs, role sign-off) is paid in week one so nothing is built on trust.

## 2. Where we are (verified, with the caveat that matters)

| Track | Milestone | State | Evidence | Caveat |
| --- | --- | --- | --- | --- |
| Capture | Phase 1/2 managed resolver | Merged, opt-in | [overview](../CALCIFY_PHASES_OVERVIEW.md) | Structural policy only |
| Capture | D7 standing-liquidity, local RF1, 300s | **10,425.19 verified/s** | [ledger](../THROUGHPUT_BASELINES.md) | Fresh state; no aged identities or contention |
| Capture | Aged contention, resolver **cold-restarted** | **8,107-8,145 full contexts/s: 10k gate fails** | [aged proof](../research/CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md) | One host, 300s |
| Capture | Matched control, **no restart** | **10,321-10,350/s: passes** | [control](../research/CALCIFY_MATCHED_CONTROL_2026-10-11.md) | Recovery cost not measured by it |
| Financial | E0-E4 test-only unreserved gross DvP | Correct; timed target **missed** (1,051.73 settled/s vs 2,500/s at 60s) | [E4](../evidence/calcify-financial-sprint1/README.md) | Harness, not product |
| Finite P3 | P0 gate, O1 capture model | Merged | [O1](../evidence/calcify-finite-p3/README.md) | Fixture scale |
| Finite P3 | **O2** durable ingress/binding/restore | Merged; reviews 2 and 3 "Ready with non-blocking follow-ups" | [O2 verification](../evidence/calcify-finite-p3/2026-10-11-o2-verification.md) | G1-G8/G10 live evidence inherited, **not replayed**; role grants not signed off |
| Finite P3 | **O3a** pure typed ledger | Merged; **independent review 1 of 3: Ready with non-blocking follow-ups** | this plan, section 8 (S1-A) | 22 of 36 test mutants survive; budget policy lets junk starve funding |
| Finite P3 | O3 packet | D1-D7 decided | [packet](CALCIFY_FINITE_P3_O3_PACKET.md) | Binding v2 unspecified |
| Goalpost | 10k/s through the trading API, settlement lags | Owner-defined; numbers proposed | [arena contract](CALCIFY_ARENA_THROUGHPUT_CONTRACT.md) | Not adopted |
| Credit | Receivable credit, 25%/10% | Proposal with reference proofs | [credit policy](CALCIFY_ARENA_CREDIT_POLICY.md) | Uncalibrated; separate profile |

Hosted CI is Linux/Node 22/Bun 1.3.14; the finite harness is macOS-local (`make test-finite-o2-harness`); the Go coverage gate (76%)
passes by 0.3 points only because the fixture-only probe is excluded.

## 3. Are we on the right path?

**Yes, with seven course corrections.** Three independent looks (RFC, pressure test, O3 decisions) agree on: durable capture, one financial
writer per closed run domain, ordered admission of source plus controls, incremental keyed state, atomic SQL projection with a checkpoint.
The 10k capture gate is reachable on the current host without restarts.

| Correction | Why | Where scheduled |
| --- | --- | --- |
| C1 Never scale finite artifacts (single serialized state image, finite binding, finite budgets) | The pressure test: "bounded oracle, unsafe scale-up template" | Capacity lane B (incremental reducer) |
| C2 Recovery is the critical path to 10k | 8.1k/s with cold restart vs 10.3k/s without | S1-D warm-restart proof; B-track design |
| C3 Two gates, one bridge | Finite P3 proves semantics on a toy; the product goal is rate through the API. O3 is built as the booking kernel (per-entity keys, versioned bytes). Indicative scratch run: **~170k decisions/s** single-thread incl. SHA-256, chain and invariants (copy-per-decision state, finite fixture) = ~17x the 10k/s need; **not** product evidence, re-measure on the store-backed path (S2-A) | S1-A (budget policy), S2-A |
| C4 Credit is a separate profile | O3 stays gross unreserved; reservations need calibration plus an owner decision | S3-E |
| C5 Pay evidence debt first | Inherited G1-G8/G10; Records PR11/PR12 unmerged (merge-commit, Reef links hash-pinned); role sign-off; `assertOwned` under a broker blip | S1-C |
| C6 Keep docs short | One living plan, short board, history log (done in this change) | Done |
| C7 Hosted-CI parity first | Replay CI steps in a clean checkout; macOS tooling stays out of required CI | Standing rule |

## 4. Is more research blocking real implementation? No.

| Open item | What it gates | Blocks code? | Handling |
| --- | --- | --- | --- |
| Warm-restart proof at aged state | The 10k claim and hardware sizing; may shape the *capacity* lane's recovery design (standby, reserve) | No. O3b/O4/O5 and the keyed reducer proceed | Run in Sprint 1 in parallel; if it fails, the capacity lane changes, not the finance lane |
| Credit calibration | Reservation/credit code | No (none is planned) | Sprint 3 research; owner decision P-5 |
| Arena target adoption (1s/5s/30s), run length, hardware ceiling, retention window | Capacity *qualification* | No | Owner decisions in Sprint 3; frozen before any qualification run |
| Option-B ordering determinism | O3b design | Mild: 2-hour spike *inside* O3b design, not research | S1-B2 |

## 5. Decision register

| ID | Decision | State |
| --- | --- | --- |
| O3 D1-D7 | Generic ledger; unreserved gross DvP; no durable credit redesign; operator controls; budgets; two units; option B ordering | Decided (owner, Oct 11) |
| P-1 | Goalpost boundary | Owner-defined; numbers **adopt in S3-E** |
| P-2 | Size bounds by max-field reflection test | Default adopted |
| P-3 | Binding v2 as a new schema version | Default adopted; confirm in S1-B1 review |
| P-4 | Budget policy: admission filter (malformed/unauthorized inputs are refused by the adapter, not recorded, not counted) plus separate counters for captures, controls (8) and settle attempts | **Recommended default; decide in S1-A review** (O3a review finding F2) |
| P-5 | Reservation/credit adoption | Open until calibration |
| P-6 | Hardware ceiling, run length, retention window, recovery target, capture freshness, tolerated settlement age | Open; freeze before qualification |

## 6. Working rules

Review: fresh-context reviewer, neutral bootstrap separate from the author explanation, max 3 instances per delivery unit, splits declared
up front. Tests: direct tests and mutation checks over bespoke supervisors; 20-minute cumulative blocker timebox then escalate; one approved
attempt per live run; no custom monitors. Evidence: every run recorded with command, environment, result, limits. CI: clean-checkout replay;
`git add` exact paths only. Claims: none for capacity, authority or adoption from model, fixture or research tests. Merges: owner decides;
**no merge of unreviewed code** (the auto-mode guard blocked one); squash for Reef, merge-commit for Records.
File leases: lane A owns `calcify/finance/**`, finance proto, binding v2 files; lane B owns `calcify/capture/**` (new) and the keyed reducer tests;
shared files (`Main.kt`, `ci.yml`, `WORK_PLAN.md`, migrations) need a lease from the manager session.

## 7. Target design for the build (what the sprints implement)

**Finance path (lane A).** `CAPTURE` envelopes (`READY_EXECUTION` members only; `REPLAY` members ignored) and operator `CONTROL` inputs feed one
financial processor (Kafka Streams, EOS v2, single partition per run). Each accepted input gets the next position in the processor's own
transactional history (option B). Per input: map to a `FinanceInput` (accounts from the two order acceptances checked against the opening's
`parties`; assets from `quoteCurrency` and `baseAssetId`; action key derived from the execution key so crash re-delivery dedupes), call the pure
`decide`, write the changed per-entity store keys (`pos/<account>/<asset>`, `exe/<key>`, `obl/<key>`, `att/<key>/<n>`, `act/<actionKey>`, `meta`) and emit
one record to `FINANCE_DECISIONS` in the same transaction. After a `CAPTURED` decision the processor derives the automatic settlement attempt 1
deterministically (so "pending, zero legs" arises from a recorded `INSUFFICIENT_CASH`). Repairs are `CONTROL` inputs. Decision records are
self-describing (input bytes plus decision) so the O4 projector can apply row deltas without the processor. Binding v2 adds roles `CONTROL`,
`FINANCE_DECISIONS` and `FINANCE_CHANGELOG`; restore cut = CAPTURE offset + CONTROL offset + finance history position.

**Visibility (O4).** A projector consumes `FINANCE_DECISIONS` and writes positions, obligations, attempts, exceptions and a checkpoint
`(run, epoch, last_position, chain_tip)` in one SQL transaction (epoch/version fenced, crash-after-commit replays once); read routes are
run/account authorized (existing settlement routes today authenticate but do not authorize by object).

**Capture at scale (lane B).** `IncrementalLifecycleReducer` beside the frozen O1 reducer: per-entity keys (`ord/<id>`, `bat/<id>`, `exe/<id>`,
frontier and fault markers), validate each transition locally, emit the same `FiniteLifecycleCaptureV1` bytes; exhaustive validation only at
certify/restore. Proven equal to the frozen reducer by a differential harness (reorder, duplicate, corrupt, missing member, closure burst,
invalid restored history). Opt-in live mode follows once equal.

## 8. Sprint 1 — "Harden, specify, and start building" (5 working days)

**Goal:** a trustworthy O3a, a specified and spike-validated O3b, the first lines of the keyed capture reducer, and evidence debt paid.
**Exit:** O3a hardened and re-reviewed (Ready or Ready with follow-ups); binding v2 spec reviewed; option-B spike verdict; reducer core with
passing differential tests on valid schedules; O2 live replay and Records PRs done or explicitly blocked on the owner; warm-restart verdict recorded.

| ID | Lane | Task (concrete) | Acceptance | Est. |
| --- | --- | --- | --- | --- |
| S1-A1 | A | **Close O3a review F1**: add assertions for each surviving mutant (changed-price duplicate => `EXECUTION_CONFLICT`; `PRIOR_ATTEMPT` via a new action key; `attempt<1`, `buyer==seller`, negative price, zero quantity => `INVALID_INPUT`; authority-only and attempt-only digest differences => `ACTION_CONFLICT`; leg-edited decision fails `verifyChain`; tampered `actions`/`attempts`/`chain` and invariant-violating checkpoints fail `restore`; negative-position and balance-sum checks) | Commit the review's 36-mutant check as a repeatable Bun script (`scripts/dev/calcify-financial/research/finance-mutation-check.mjs`; mutation list from the review, kept locally as `mut.py`/`mut.out` under `.planning/calcify-o3a-review1/review/`) plus new mutants: **>=95% killed** | 1d |
| S1-A2 | A | **Independent oracle**: a deliberately simple second ledger in test sources (map-based, different code path), >=5,000 seeded schedules with retries, conflicts, refusals, overflow, restore cuts; dispositions, legs and states equal | Oracle agrees; a mutated production ledger is caught | 1d |
| S1-A3 | A | **Budget policy P-4** in `FinanceLedger`: separate counters (captures <=128 and obligations <=256; controls <=8; settle attempts), refusals that never reach the ledger are not counted; recorded-disposition enum split from non-recorded (`PRIOR_RESULT`, `ACTION_CONFLICT`, `BUDGET_EXCEEDED`); skip zero-amount legs or reject zero price; capture precedence (conflict before validation) decided | Test: 1,000 junk `TRADING` funds cannot block an operator fund; policy written into the packet | 0.75d |
| S1-A4 | A | **Strict text**: reject unpaired surrogates, control characters; `restore(checkpoint, expectedHead, configDigest)` binds history head and config; config digest in genesis chain | New tests (surrogate pair collision, forged checkpoint) fail before, pass after | 0.5d |
| S1-A5 | A | **Pin bounds and protobuf wire** (packet C5): max-field reflection test for decision record and per-entity entries; additive `contracts/proto/calcify_finance.proto`; canonical-bytes golden fixtures cross-checked by an independent encoder script | Bounds documented in the packet; goldens committed | 1d |
| S1-A6 | A | **O3a re-review (instance 2 of 3)** after A1-A5; fixes recorded finding by finding | Verdict Ready or Ready with follow-ups | 0.5d + fixes |
| S1-B1 | A | **Binding v2 specification** `docs/work/CALCIFY_FINITE_P3_BINDING_V2_SPEC.md`: schema id `calcify-finite-binding-v2`; new roles `CONTROL`, `FINANCE_DECISIONS`, `FINANCE_CHANGELOG`; application id and store name; `calcify_topic_identities` role CHECK; v1 untouched and readable; golden-bytes plan; Go and Kotlin parser plan; control-writer location (P-4/P-3 confirmation); ACL and isolation proof pattern; restore cut and refusal rules | Spec complete; independent review (docs) Ready | 1d + 0.5d review |
| S1-B2 | A | **Spike: option-B determinism** (2h timebox): `TopologyTestDriver` processor over two input topics with its own recorded positions; two interleavings each reproduce from history; crash between consume and record loses/duplicates nothing | Go/no-go recorded; on no-go escalate to owner (option A) | 0.5d |
| S1-C1 | C | **Records PR11/PR12**: merge with the **merge-commit** method; link check proves every `reef-records` URL in Reef docs resolves | Check passes | 0.25d (owner merges) |
| S1-C2 | C | **O2 live replay of G1-G8/G10 on master**, one approved attempt on the existing harness (plain Compose, no new monitor); acceptance per the [matrix](../../.planning/calcify-p3-o2-2026-10-10/manager/acceptance-matrix.md); include a 5-second broker interruption to answer the `assertOwned` question | Results recorded in the O2 verification record or a blocker report; timebox 2h | 0.5-1d |
| S1-C3 | C | **Role definition sign-off** with the operator; add default-privileges and owner-membership checks to the verification query | Signed off or assigned | 0.25d |
| S1-D1 | B | **Warm-restart proof** per the [control doc](../research/CALCIFY_MATCHED_CONTROL_2026-10-11.md) "Next proof": restart with a compatible retained resolver state directory at the same age under the same workload; frozen interruption allowance; instrumented. Gate: capture >=10k/s over 300s including the interruption; on failure isolate **one** variable | Verdict recorded with attempts and limits | 1-1.5d (owner approves runs) |
| S1-D2 | B | **Keyed capture reducer, core**: `IncrementalLifecycleReducer` with an in-memory KV abstraction, order/execution/batch keys, local transition validation, byte-equal envelopes for valid schedules | Differential tests vs frozen reducer pass on valid schedules (>=2,000 seeded) | 2.5d |

**Order:** day 1 A1, B1 start, D2 start, C1; day 2 A2, A3, B2, D2; day 3 A4, A5, C2, D1 run; day 4 A6, B1 review, D2; day 5 buffer, exit check.

## 9. Sprint 2 — "Durable financial admission" (5 working days)

**Goal:** inputs flow from `CAPTURE` and `CONTROL` through the financial processor to an append-only decision log on a real broker, restore correctly, and are operator-gated.
**Exit:** O3b merged after review; reducer differential harness complete; O4 schema and projector tested on real Postgres in CI; Sprint 1 evidence published.

| ID | Lane | Task | Acceptance | Est. |
| --- | --- | --- | --- | --- |
| S2-A1 | A | **Binding v2 implementation**: Go and Kotlin parsers, golden bytes and manifest, topic-role CHECK migration (pattern of `0076`), migration-order test entry, v1 compatibility tests | Cross-language goldens match; v1 fixtures untouched; CI replay clean | 1.5d |
| S2-A2 | A | **Financial processor** (`calcify/finance/` adapter): CAPTURE+CONTROL consumption, input mapping, per-entity store, decision log output, auto attempt 1, restore cut and refusals; reuse `decide` unchanged | Crash matrix on `TopologyTestDriver` (before/after record, after output, restart); two-interleaving test; per-entity store cost measured (decisions/s, bytes/key) | 3d |
| S2-A3 | A | **Control writer**: operator-authenticated, exclusive topic ACL, idempotent by action key, charged to the 8-control budget; trading principals refused (tested) | Authorization tests; Postgres/ACL pattern as O2 | 1d |
| S2-A4 | A | **One real-broker test** (G9 style, disposable Redpanda, cached image): capture 3 fixture trades, fund, 3 repairs, restart, state equal; record result | 1 test, 0 skipped; recorded | 0.5d |
| S2-A5 | A | **O3b independent review** (cap 3) | Ready or Ready with follow-ups | 0.75d + fixes |
| S2-B1 | B | **Reducer differential harness complete**: reorder, duplicate, corrupt, missing member, closure burst, invalid restored history; Streams-store adapter behind the KV abstraction | All cases equal to frozen oracle; independent review | 2.5d |
| S2-B2 | A/B | **O4 schema + projector** (starts day 2 on O3a types, frozen to O3b records at day 4): separate namespace migration; projector with one transaction per record batch, epoch fence, checkpoint; Postgres suite wired like the finite one with the skip guard | Crash-after-commit applies once; stale epoch refused; CI Postgres job green | 3d |
| S2-C1 | C | Publish Sprint 1 evidence (Records), refresh plan and board | Board matches plan | 0.5d |

**Order:** day 1 A1; day 2 A2 start, B2 start, B1; day 3 A2, A3; day 4 A4, B2 freeze; day 5 A5 review, buffer. If time slips, cut **B1 live opt-in and C1 polish first**, never reviews.

## 10. Sprint 3 — "Visible end to end, and the 10k bridge" (5 working days)

**Goal:** a trade is visible through the API as booked with settlement pending, funded and settled once, restorable; and the first measured slice toward the 10k goal.
**Exit:** O4 and O5 merged after review; keyed reducer opt-in mode with live restore tests; goalpost numbers adopted or edited by the owner; capacity qualification manifest frozen.

| ID | Lane | Task | Acceptance | Est. |
| --- | --- | --- | --- | --- |
| S3-A1 | A | **O4 read routes**: run/account authorized `positions`, `obligations`, `progress`; common snapshot rows plus progress; no full-history rescan | Allowed/denied tests in both HTTP adapters; review | 2d |
| S3-A2 | A | **O5 end-to-end fixture**: finite launcher; 3 trades pending (zero legs) -> fund -> 3 distinct repairs settle once; restore source/admission/owner/SQL cuts; exact API rows, progress and journal | Test plus one recorded live run (single approved attempt); finite stage costs recorded, no capacity claim | 2d |
| S3-A3 | A | **O3b/O4/O5 integration review** (separate declared unit, cap 3) | Ready or Ready with follow-ups | 0.75d + fixes |
| S3-B1 | B | **Keyed reducer opt-in live mode** behind the finite profile; restore tests (same binding), compare against frozen reducer on the fixture | Fixture output byte-equal; refusal rules intact | 2d |
| S3-B2 | B | **Order-to-API slice instrumentation plan and harness** (paired-order workload per the arena contract): stage timestamps from accept to API-visible booked trade; no rate claim | Harness runs at low rate with all stages visible; plan reviewed | 1.5d |
| S3-E1 | C | **Credit calibration** with the PR490 reference model on paired-order workloads; recommendation recorded | Owner decides P-5 | 1d |
| S3-E2 | C | **Goalpost adoption and qualification manifest**: owner adopts or edits 1s/5s/30s; freeze workload labels, source profile, host, state age, observers and P-6 list | Manifest committed | 0.5d |

**Held for Sprint 4 (not planned in detail):** paired-order rate and duration ladder (600s at 10k, then 7.5k for 900s), co-resident qualification, recovery under load,
authority decision only if measured cost is unacceptable (pressure-test step 7).

## 11. First day, concretely

1. Owner: merge Records PR11 and PR12 with the merge-commit method; approve one live replay window and one warm-restart window.
2. Lane A session: branch `claude/calcify-o3a-harden` from master; S1-A1 first (the reviewer's survivor list is the task list), then A2 and A3 in the same branch.
3. Lane A (second session): branch `claude/calcify-binding-v2-spec`; S1-B1 (docs only), then the 2-hour determinism spike on its own branch.
4. Lane B session: branch `claude/calcify-keyed-reducer`; create `calcify/capture/IncrementalLifecycleReducer.kt` and the differential harness skeleton against `FiniteLifecycleReducer`.
5. Manager session: lease table for shared files; review bootstrap templates from `.planning/calcify-o2-finish-review2` as the pattern.

## 12. Risks and stop conditions

| Risk | Mitigation | Stop condition |
| --- | --- | --- |
| Warm restart also misses 10k | Isolate one variable (reserve, parallelism, standby) | Two failed isolated variables: escalate with the measured bottleneck |
| Store-backed booking is too slow | S2-A2 measures decisions/s on the real store; batch per-record chain (needs review) | <50k decisions/s: re-plan representation before O4 |
| Option-B spike fails | Fall back to option A (heavier binding rewrite; owner gate) | Fails twice |
| Live runs stall on harness limits | Existing harness as-is, one attempt, 2h timebox | 20-minute blocker timebox then escalate |
| Review loops dominate | Mutation checks and oracles before review; declared splits | Cap reached: human decision |
| Parallel lanes collide | Disjoint file leases; manager owns shared files | Any shared-file edit without a lease |
| Scope creep from credit/arena research | P1a stays gross unreserved; adopt only by owner decision | None: it is a gate |

## 13. Canonical owners

| Question | Owner |
| --- | --- |
| What is next and why | this document, then the [work board](../WORK_PLAN.md) |
| Finite lifecycle, budgets, ingress | [source contract](CALCIFY_FINITE_P3_SOURCE_CONTRACT.md), [contracts README](../../contracts/calcify/README.md) |
| O3 design and decisions | [O3 packet](CALCIFY_FINITE_P3_O3_PACKET.md) |
| Production role split | [role definition](CALCIFY_FINITE_P3_DB_ROLES.md) |
| Goalpost and timing targets | [arena throughput contract](CALCIFY_ARENA_THROUGHPUT_CONTRACT.md) |
| Credit limits (proposal) | [arena credit policy](CALCIFY_ARENA_CREDIT_POLICY.md) |
| Measurements | [throughput ledger](../THROUGHPUT_BASELINES.md) and dated evidence under `docs/evidence/` |
| History | [history log](CALCIFY_HISTORY_LOG.md) |
