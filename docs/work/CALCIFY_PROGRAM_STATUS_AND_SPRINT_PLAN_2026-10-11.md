# Calcify program status and two-sprint plan — October 11, 2026

Status: **planning document; proposes no authority, makes no capacity claim, changes no contract.** It is the single
place that states where Calcify stands, whether the path is sound, and what to do next. Execution status stays on the
[work board](../WORK_PLAN.md); measurements stay in the [throughput ledger](../THROUGHPUT_BASELINES.md); architecture stays
in the [RFC](CALCIFY_SYSTEM_ARCHITECTURE_RFC.md). Where this document and a dated evidence record disagree, the evidence
record wins for its stated scope. Baseline: `master` after [PR488](https://github.com/dills122/reef/pull/488) (O2),
[PR490](https://github.com/dills122/reef/pull/490) (research), [PR489](https://github.com/dills122/reef/pull/489) (O3 packet,
role definition) and [PR491](https://github.com/dills122/reef/pull/491) (O3a module) are merged.

## 1. Where we are (verified, with caveats)

| Track | Milestone | State | Evidence | Caveat that matters |
| --- | --- | --- | --- | --- |
| Production Phase 1/2 | Durable match pointers, verification, managed full-context resolver | Merged, opt-in | [Phases overview](../CALCIFY_PHASES_OVERVIEW.md) | Structural policy only; no financial meaning |
| Capture throughput | D7 standing-liquidity, local RF1 | **10,425.19 verified/s, 10,440.92 resolved/s, 300s** (Oct 2) | [ledger](../THROUGHPUT_BASELINES.md) | Fresh state, no aged identities, no financial/SQL contention |
| Financial experiments | E0-E4 unreserved gross DvP (test-only) | Correctness passed; **timed settlement target missed**: 1,051.73 settled/s vs 2,500/s at 60s | [E4 checkpoint](../evidence/calcify-financial-sprint1/README.md) | Harness, not product; eventual completion is not deadline throughput |
| Finite P3 | P0 source profile, O1 lifecycle capture model | Merged (PR479, PR485) | [O1 evidence](../evidence/calcify-finite-p3/README.md) | Fixture-scale: 16 publications, 8 orders |
| Finite P3 | **O2** durable ingress, binding, stop/drain, restore | **Merged** (PR488, `85115ac80`); independent reviews 2 and 3 both "Ready with non-blocking follow-ups" | [O2 verification](../evidence/calcify-finite-p3/2026-10-11-o2-verification.md) | G1-G8 and G10 live evidence is Codex-recorded, **not independently replayed**; role grants inferred until operator sign-off; Records archive not published |
| Finite P3 | **O3** financial admission | Packet (decisions D1-D7) and **O3a pure module merged, unreviewed** (14 tests) | [O3 packet](CALCIFY_FINITE_P3_O3_PACKET.md) | Not wired; no wire format; no pinned size bounds; binding v2 not specified |
| Research (PR490) | Single-server pressure test, aged contention, matched control, arena contract, credit proposal | Merged as research/proposals only | [pressure test](../research/CALCIFY_SINGLE_SERVER_ARCHITECTURE_REVIEW_2026-10-11.md), [aged](../research/CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md), [control](../research/CALCIFY_MATCHED_CONTROL_2026-10-11.md) | See section 2 |

Hosted-CI parity lessons are part of the state: CI is Linux/Node 22/Bun 1.3.14; the finite harness is macOS-local
(`make test-finite-o2-harness`); the Go coverage gate (76%) passes with a thin margin only because the fixture-only probe is excluded.

## 2. What the newest evidence says about the goal

The owner's goalpost (PR490) is now: **10,000 real user/bot orders per second matched, booked in Calcify and visible
through the trading API as completed trades awaiting settlement**; settlement may lag within bounded clocks; one server, larger
allowed with evidence. Booking and credit accounting sit **inside** the 10k gate. Numeric targets are still proposals
(p99 visibility 1s, eligible settlement 5s, session close 30s; see the [arena contract](CALCIFY_ARENA_THROUGHPUT_CONTRACT.md)).

| Observation (local 10-CPU / ~15.6 GiB Docker VM, 300s) | Result |
| --- | --- |
| Aged treatment, resolver **cold-restarted** mid-run | Capture ≥10,172/s lower bound, but **full contexts 8,107-8,145/s: 10k gate fails** |
| Matched control, **no restart** | **10,321-10,350 full contexts/s: passes**; aged 60-300s lower bound 10,296/s |
| Post-180s resolved bounds | control 10,411-10,572/s vs treatment 5,539-5,706/s |

Reading: steady aged capture can hold 10k on this host; **cold recovery of resolver state is what breaks the gate**, and
identity-retention budget plus co-resident resource cost are still open. Neither run exercised the finite O2 path, the
user-facing API, booking or credit. The pressure test also says the finite whole-state-image design is "a bounded oracle, an unsafe scale-up
template" (it rewrites one growing image per event), and recommends incremental keyed state with differential testing against the finite oracle.

## 3. Are we on the right path? Assessment

**Sound, keep going:**
1. The correctness ladder works. O1 and O2 each landed after independent review, found real defects (the NUL fence, the compression pin) and were fixed with tests. Keep fresh-context review with a 3-instance cap.
2. The architecture direction is consistent across three independent looks (RFC, pressure test, our O3 decisions): durable capture, one financial writer per closed run domain, ordered admission of source plus controls, incremental keyed state, atomic SQL projection with a checkpoint. O3 decisions D1-D7 match it (generic typed ledger, unreserved gross DvP first, order recorded in the processor's own history).
3. The 10k capture gate is reachable on this host without restart, so the work is bounded: recovery and co-residency, not a fundamental redesign.

**At risk; course corrections (each is scheduled below):**
- **C1. Do not scale finite artifacts.** The O1 reducer's single serialized state, the binding schema and the finite budgets are fixture oracles. Any 10k integrated claim needs the incremental keyed reducer first (Sprint 2, S2-C). Keep finite acceptance and rate qualification separate, as the pressure test insists.
- **C2. Recovery is the critical path to 10k.** Run the warm-restart experiment (S1-D) before building more features on an unproven recovery story.
- **C3. Two gates, one bridge.** Finite P3 proves correctness on a toy; the product goal is rate through the API. The bridge is O3a/O3b designed as the booking kernel: per-entity keys, versioned canonical bytes. Measure its per-decision cost early (S1-B4); an indicative scratch run (October 11, Java 21, 10-core laptop, JIT warm, 158-input mixed cycles, state ≤160 entries, not committed) measured about **170,000 decisions/s** single-threaded including SHA-256 digests, the hash chain and a full invariant check per decision, roughly 17 times the 10k/s need. This is **not** evidence for the product: the module copies its maps on every decision, which cannot scale beyond the finite fixture; the real adapter must be re-measured against a per-entity store.
- **C4. Credit is a separate profile.** O3 stays gross unreserved (P1a). The proposed receivable-credit policy (25% account cap, 10% funded pool, reserve before matching) needs workload calibration and an owner adoption decision before any reservation code. Keep O3a's canonical encoding versioned so reservation inputs can be added without breaking digests.
- **C5. Pay evidence debt first.** Inherited G1-G8/G10 evidence, unmerged Records PRs ([reef-records#11](https://github.com/dills122/reef-records/pull/11), [#12](https://github.com/dills122/reef-records/pull/12)), role sign-off and the `assertOwned` outage question must not be built on silently. Merge Records PRs with the **merge-commit** method: Reef docs link immutable commit SHAs, and a squash would break them.
- **C6. Cut documentation drag.** The work board accumulated hour-by-hour entries (hundreds of lines). One living status plan (this document) plus a short board per active task; stop adding checkpoint essays.
- **C7. Hosted-CI parity first.** Replay CI steps in a clean checkout before pushing; keep macOS-only tooling out of required CI.

## 4. Decision register

| ID | Decision | State |
| --- | --- | --- |
| O3 D1-D7 | Generic ledger; unreserved gross DvP; no durable credit redesign; operator controls; budgets; two units; **option B** ordering | Decided (owner, Oct 11) |
| P-1 | Goalpost boundary (10k through API, settlement lags) | Owner-defined; numeric targets proposed, **adopt in S2-E** |
| P-2 | Serialized-bound method: max-field reflection test (not the reference kernel's 64 KiB) | Default adopted; confirm in review |
| P-3 | Binding v2 as new schema version (v1 frozen) | Default adopted; confirm in S1-C review |
| P-4 | Control-writer location (ingress adapter path vs separate component) | Decide in binding v2 spec (S1-C1) |
| P-5 | Reservation/credit adoption | **Open**; decide after S2-D calibration |
| P-6 | Hardware ceiling, run length, retention window, recovery-time target, capture freshness, tolerated settlement age | **Open**; freeze before any capacity qualification |

## 5. Working rules for both sprints

Review: fresh-context reviewer, neutral bootstrap separate from the author explanation, max 3 instances per delivery unit,
declared splits up front. Tests: direct tests over bespoke supervisors; 20-minute cumulative blocker timebox then escalate;
one approved attempt per live run, no custom monitors. Evidence: every run recorded with command, environment, result,
limits; failures retained. CI: clean-checkout replay; never `git add -A`. Claims: no capacity, authority or adoption claim
from model, fixture or research tests. Merges: owner decides; squash for Reef, merge-commit for Records.

## 6. Sprint 1 — "Ground truth and de-risk" (5 working days)

**Goal:** stand on verified ground, finish O3a, de-risk O3b's ordering design, and answer the recovery question.
**Exit criteria:** O2 loose ends closed or explicitly owned; O3a reviewed (Ready or Ready with follow-ups); binding v2
specification reviewed; option-B determinism spike has a verdict; warm-restart experiment has a recorded verdict; work board consolidated.

| ID | Task | Detail and acceptance | Owner | Est. | Depends |
| --- | --- | --- | --- | --- | --- |
| S1-A1 | Merge Records PR11 and PR12 | Merge-commit method; afterwards a link check proves every `reef-records` commit URL in Reef docs resolves | Owner merges; Claude verifies | 0.25d | none |
| S1-A2 | Finish this doc sync | Stale statements fixed (section 9), board consolidated, plan indexed | Claude | 0.5d | PR489/491 merged |
| S1-A3 | Role definition sign-off | Operator approves [role matrix](CALCIFY_FINITE_P3_DB_ROLES.md); add `ALTER DEFAULT PRIVILEGES` / owner-membership checks to the verification query | Operator + Claude | 0.25d | none |
| S1-A4 | **O2 live replay of G1-G8/G10** on master | One approved attempt on the existing harness (`finite-o2`, plain Compose, no new monitor). Acceptance: canonical 12/12/12 flow; same-binding restart; crash and budget cases in the [acceptance matrix](../../.planning/calcify-p3-o2-2026-10-10/manager/acceptance-matrix.md); includes a 5-second broker interruption probing `assertOwned`. Timebox 2h; on failure stop and report | Claude drives; owner approves the run | 0.5-1d | A1 optional |
| S1-B1 | Pin O3a bounds (packet C5) | Max-field reflection test for decision record and per-entity entries; pin and document limits | Claude | 0.5d | none |
| S1-B2 | O3a protobuf wire | Additive `contracts/proto/calcify_finance.proto`; canonical bytes with golden fixtures cross-checked by an independent encoder script; digests unchanged in meaning | Claude | 1d | B1 |
| S1-B3 | Independent oracle and differential test | Second, deliberately simple implementation in test sources; at least 5,000 seeded schedules including retries, conflicts, overflow, restore cuts; states and dispositions equal | Claude | 1d | none |
| S1-B4 | Per-decision cost microbenchmark | Indicative scratch result recorded in section 3 (C3). Task: commit a reproducible benchmark with config and JVM, measure decide cost separately from invariant checking, and add the per-entity-store variant once S1-C3 exists; **gate:** ≥50k/s on the store-backed path or a written optimization note | Claude | 0.25d | S1-C3 for the store-backed variant |
| S1-B5 | O3a independent review | Fresh context; max 3; fixes recorded finding by finding | Claude + cold reviewer | 0.5d + fixes | B1-B3 |
| S1-C1 | Binding v2 specification | `docs/work/CALCIFY_FINITE_P3_BINDING_V2_SPEC.md`: schema id, new roles (CONTROL, financial changelog), application id and store name, golden-bytes plan, `calcify_topic_identities` CHECK, v1 compatibility, Go/Kotlin parser plan, restore cut (CAPTURE offset, CONTROL offset, financial history position), control-writer location (P-4), isolation/ACL | Claude | 1d | none |
| S1-C2 | Independent review of packet plus spec | Docs review; neutral bootstrap | Claude + cold reviewer | 0.5d | C1 |
| S1-C3 | **Spike: option-B determinism** (timebox 2h) | `TopologyTestDriver` processor reading two topics with its own recorded history: two interleavings each reproducible from recorded history; crash between consume and record loses/duplicates nothing. Verdict go/no-go for option B before O3b | Claude | 0.5d | B2 optional |
| S1-D1 | **Warm-restart proof** | Per the [matched-control next proof](../research/CALCIFY_MATCHED_CONTROL_2026-10-11.md): restart with a compatible retained resolver state directory at the same age under the same financial/SQL workload; freeze the interruption allowance; instrument restoration, GC/compilation, disk latency. Gate: capture ≥10k/s over 300s including the interruption. On failure isolate **one** variable (reserve, parallelism or standby) | Owner approves each run; Claude or Codex executes with the existing research tooling | 1-1.5d | none (parallel) |
| S1-E | Board consolidation | Collapse stacked entries into current-state entries; keep one next-actions entry | Claude | 0.25d | A2 |

**Suggested order:** day 1 A1, A2, B1, B3 start; day 2 A4, B2, C1; day 3 B4, B5, C3, D1 run; day 4 C2, review fixes; day 5 buffer, exit check.

## 7. Sprint 2 — "Durable financial admission and scalable state" (5 working days)

**Goal:** make O3b real, start O4, and begin the scalable-state track while credit and goalpost decisions are made.
**Exit criteria:** O3b reviewed and merged; O4 projector tested against real Postgres in CI; incremental-reducer differential
oracle passing; credit calibration recorded with an owner decision; capacity qualification manifest drafted.

| ID | Task | Detail and acceptance | Est. | Depends |
| --- | --- | --- | --- | --- |
| S2-A | **O3b adapter** | Binding v2 implementation (Go and Kotlin parsers, golden bytes, topic-role CHECK migration); Streams financial processor over CAPTURE plus CONTROL with per-entity store and append-only decision log; operator-authenticated control writer with exclusive ACLs; restore-cut refusals. Tests: `TopologyTestDriver` crash matrix; **one** real-broker transport test in the G9 style; no hot-path scans. Independent review max 3 | 4d | S1-B5, S1-C2, S1-C3 go |
| S2-B | **O4 SQL/read seam** | Separate namespace migration; projector writing journal, accounts, obligations, exceptions and checkpoint in one transaction; epoch/version fence; crash-after-commit replays once; run/account authorization on the existing settlement routes (route existence is not authorization); Postgres suite wired like the finite one with the skip guard | 3d (parallel from day 2 using O3a types) | O3b interface frozen |
| S2-C | **Incremental keyed capture reducer** (proof step 3) | New component beside the frozen O1 oracle; differential tests vs the oracle (reorder, duplicate, corrupt, missing member, closure burst, invalid restored history); no live claim; independent review | 3d | none |
| S2-D | **Credit calibration** | Run the PR490 reference model on paired-order workloads to calibrate 25%/10%/80%/30s; record outcomes and a recommendation; owner decides P-5 | 1d | none |
| S2-E | **Goalpost adoption and manifest** | Owner adopts or edits the arena targets; draft the capacity qualification manifest (workload labels, source profile, host, initial state age, observers, freeze list for P-6) | 0.5d | S2-D |
| S2-F | Records and doc hygiene | Publish Sprint 1 evidence; refresh this plan; consolidate board | 0.5d | all |

**Held for Sprint 3 (not planned here):** O5 end-to-end fixture, paired-order rate and duration ladder, order-to-API instrumentation, co-resident
qualification at 600s, authority decision if measured cost is unacceptable (pressure-test step 7). They depend on O3b/O4 acceptance and the recovery verdict.

## 8. Risks and stop conditions

| Risk | Mitigation | Stop condition |
| --- | --- | --- |
| Warm restart also misses 10k | Isolate one variable; consider standby or smaller state before hardware | Two failed isolated variables: escalate to owner with the measured bottleneck |
| Booking kernel too slow with chain and digest | S1-B4 gate; optimize encoding; chain per batch rather than per decision (needs review) | Cannot reach 50k decisions/s: re-plan O3 representation before O3b |
| Option-B spike shows nondeterminism | Fall back to option A with a heavier binding rewrite (owner gate) | Spike fails twice |
| Live runs stall on harness limits | Existing harness as-is, one attempt, timebox | 20-minute blocker timebox then escalate |
| Review loops dominate | Declare unit splits up front; pre-review with differential oracles | Cap reached: human decision |
| Scope creep from credit/arena research | Keep P1a gross unreserved; adopt credit only by owner decision | None: it is a gate, not work |

## 9. Documentation sync checklist (done in the same change as this plan)

- WORK_PLAN: one current-status entry pointing here; O3 entry corrected (O3a exists, merged, unreviewed); O2 entry's "hosted CI run" open item closed.
- Stale statements fixed: "O2 implementation in progress / independent review pending" in `docs/CALCIFY_PHASES_OVERVIEW.md`, `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md`, `docs/API_BOUNDARY_STORAGE_DECISIONS.md`, `services/platform-runtime/README.md`, `contracts/calcify/README.md`.
- `docs/README.md` indexes this plan.
- Not changed: dated evidence records, accepted decisions, public contracts.

## 10. Canonical owners (where to look)

| Question | Owner |
| --- | --- |
| What is next and why | this document, then [work board](../WORK_PLAN.md) |
| Finite lifecycle, budgets, ingress rules | [source contract](CALCIFY_FINITE_P3_SOURCE_CONTRACT.md), [contracts README](../../contracts/calcify/README.md) |
| O3 design and decisions | [O3 packet](CALCIFY_FINITE_P3_O3_PACKET.md) |
| Production role split | [role definition](CALCIFY_FINITE_P3_DB_ROLES.md) |
| Goalpost and timing targets | [arena throughput contract](CALCIFY_ARENA_THROUGHPUT_CONTRACT.md) |
| Credit limits (proposal) | [arena credit policy](CALCIFY_ARENA_CREDIT_POLICY.md) |
| Measurements | [throughput ledger](../THROUGHPUT_BASELINES.md) and dated evidence under `docs/evidence/` |
