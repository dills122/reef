# Calcify documentation review and overnight session plan

Review date: October 9, 2026, America/Toronto. Baseline:
`51cd90eee43d257bad4f973fc9bd81f273bb4ef7`. GitHub comparison confirms remote
`master` identical to baseline; [PR479](https://github.com/dills122/reef/pull/479)
merged October 9 at 22:39 local. Initial worktree clean, detached HEAD; planning
branch `codex/calcify-overnight-plan-2026-10-09`.

Selected overnight priority: finite P3 source-to-read path, progressing through
lifecycle/coverage, admission, SQL/read and funding/repair/restart gates. Eight-hour
budget confirmed. Largest independently reviewed dependency prefix is fallback;
full chain cannot be promised. Elapsed schedule starts at launch recorded in
[WORK_PLAN](../WORK_PLAN.md), which owns current execution status. Plan governs
authorized isolated implementation; production financial adoption remains separate.

## Current position

Calcify is Reef subsystem, not separate repository. Production Phase1/2 implement
durable match pointers, structural verification, receipts and managed full-context
resolution. Financial kernel and recovery experiments remain test-only. Finite
matching profile now bounds retained order identities; full financial authority,
lifecycle capture, runtime admission and new SQL projection remain separate work.

| Git milestone | Landed scope | Boundary |
| --- | --- | --- |
| `9b2a48614`, PR462, October2 | Batched source extraction and sustained direct D7 proof | Local RF1 standing-liquidity source/resolver path |
| `64f519361`, PR466, October3 | Full system RFC and proof plan | Proposed architecture, not accepted financial cutover |
| `97924e156`, PR472, October3 | Frozen E0 inputs | Readiness inputs, not runtime financial integration |
| `a6ddafbba`, PR473, October4 | Financial correctness experiments | Test-only unreserved gross DvP |
| `dce5f2ba8`, PR474, October5 | Certified cuts and external broker recovery | Bounded E3 profile, not cluster-loss guarantee |
| `1fff91093`, PR475, October5 | Heap admission and sampled guard | Scoped protection, not whole-process capacity |
| `863503ec2`, PR477, October5 | Bounded recovery and compact owner diagnostics | Exact bounded owner/history proof |
| `85e513278`, PR478, October7 | Finite financial diagnostic and sprint exit | Measured deadline target missed |
| `51cd90eee`, PR479, October9 | Opt-in finite P0 matcher profile and snapshot binding | No total command/history budget or live lifecycle stream |

Phase1/2 and source map: [overview](../CALCIFY_PHASES_OVERVIEW.md),
[link/profile contract](../../contracts/calcify/README.md),
[Calcify protobuf](../../contracts/proto/calcify.proto). Current financial checkpoint:
[focused evidence](../evidence/calcify-financial-sprint1/README.md) and
[October6 verification](../evidence/calcify-financial-sprint1/e4-verification-2026-10-06.json).

| Observation | Supported claim | Excluded claim |
| --- | --- | --- |
| D7: 10,425.19 verified trades/s; 10,440.92 resolved contexts/s, 300s | Local RF1 standing-liquidity source path qualified | Financial settlement, paired/mixed profiles, RF3 production capacity |
| E4: all150000 settlements,300001 histories and result replay complete | Finite cohort correctness/replay passed | Timed rate target passed |
| E4 deadline:63104/60s=1051.73settled/s versus2500/s | Empirical target missed; producer132.040s, drain8.139s | Eventual completion as deadline throughput |
| Earlier1000settled/100pending, distinct JVM restore | Bounded managed-recovery parity | New150000-trade restart qualification |

Read [throughput ledger](../THROUGHPUT_BASELINES.md), original success/failure
artifacts and [performance learnings](../PERFORMANCE_LEARNINGS.md) before changing
measurement or performance. Existing direction removes harness memory optimization
from critical path; larger empirical resources do not establish conservative cost.

## Documentation review

Reading path is useful and authority boundaries mostly explicit. Main defect:
active navigation exposes historical pending tasks alongside latest checkpoint.
Correct current owners before developer dispatch; preserve historical evidence.

| Priority | Evidence at baseline | Correction task |
| --- | --- | --- |
| P1 dispatch risk | `docs/work/CALCIFY_PHASE2_IMPLEMENTATION.md:37–39` calls UUID/checkpoint protections pending and draft unmergeable. Current runtime pins `auto.offset.reset=none` and classic protocol; safety fixes landed earlier. | Reconcile guarantees against current source/tests and merged scope. Keep failed sustained duration result distinct from merge readiness. Move standalone superseded record only after verified Records publication. |
| P1 dispatch risk | `docs/work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md:3–8,37–39` still presents open E4 and draft PRs; later tasking predates PR478/479. | Replace active entry with concise current continuation or explicit historical routing to WORK_PLAN. Preserve proof and exhausted review budgets. |
| P2 | `docs/evidence/calcify-financial-sprint1/README.md:26–27` calls Records10 draft; WORK_PLAN records October7 merge `1419b7e0`. | Correct current merge status; retain immutable proof commits and original checkpoint scope. |
| P2 | `docs/README.md:31` says financial capacity unmeasured although bounded timed diagnostic exists. | Say production/full-path capacity unqualified; link measured target miss. |
| P2 | `docs/work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md:624` says new kernel proofs unrun; E1/E3 experimental proofs landed. Run-lifetime identity discussion at124–128 predates finite P0 gate. | Link bounded passed proofs and finite gate while retaining open production/adoption requirements. Do not label whole P0 or RFC accepted. |
| P2 maintainability | WORK_PLAN begins September4 alignment, then accumulates October checkpoints with pre-merge wording. | Keep newest Calcify next-action entry authoritative; reconcile mixed current/history sections in bounded retention pass. No repository-wide age sweep. |

Review uses docs, local Git history, current source samples and GitHub metadata.
Structural graph `reef-main` indexed September30 and roots another checkout;
coverage reports changed/untracked Calcify paths. Graph results treated as leads;
current source checked directly. No exhaustive implementation audit or fresh broker
qualification claimed. Current external settlement authorization also needs explicit
verification before financial read integration; route existence is insufficient.

## Session target and dependency order

Target full finite P3 chain in dependency order. Minimum useful checkpoint:
versioned lifecycle/coverage contracts plus implemented,
bounded source-prefix capture/closure and restore acceptance. If source-contract
design exceeds budget, deliver smaller reviewed unit and explicit remaining gate.
Do not claim partial chain as completed P3.

First useful financial path remains:

`finite P0 → lifecycle/coverage → financial admission/runtime → atomic SQL/read → funding/repair/restart`.

Use one isolated opt-in run, one instrument, two explicitly mapped accounts/assets,
journaled opening resources and unreserved gross DvP. Preserve current production
authority and lane ordering. Reservations, netting, external effects, legacy removal,
production migration and full throughput qualification remain outside overnight
target. Source-prefix baseline preferred unless bounded research proves missing
isolation/progress requirement. Durable credit redesign requires separate decision.

| Unit | Owner and file lease | Dependencies and acceptance |
| --- | --- | --- |
| O0 alignment,45min | Research developer; proposed source/budget spec under `docs/work/`, fixture/test inventory. Docs developer separately owns reconciliation rows above. Manager owns dispatch ledger. | Freeze baseline, profile, membership, replay cut, numeric budgets and acceptance cases. Define enforceable canonical ingress stop/drain protocol and durable profile/budget/history binding, including retry/rejection accounting and restore state. Confirm isolated implementation scope; never treat RFC as adoption ADR. |
| O1 contract/capture,120min | Source developer owns `contracts/proto/calcify.proto`, `contracts/calcify/`, new lifecycle capture module/tests in platform Calcify, generated sources. Shared matcher/pipeline files only under explicit manager lease. | Reviewed O0. Every execution and zero-trade accept/amend/cancel member has exact provenance, revision/dependency identity and explicit terminal disposition. Gap offsets valid; malformed/contradictory membership blocks declared scope. Existing Phase1 links unchanged. |
| O2 finite ingress/gate/restore,120min target | Same source developer or fresh gate developer owns new ingress budget adapter, gate/tests and finite launcher. Existing API/manual/stream ingress integration paths require explicit manager lease; ownership transferred explicitly. | Reviewed O1/O0 budget design. Enforce total attempts/history/fanout/bytes/windows through every canonical path to scoped run, including retries/rejections. Stop new admission at budget while reserved admitted closure drains. Persist identity and accounting with durable acceptance/replay; crash/restore cannot reset budget. High watermark leaves admitted closure reachable; exact frontiers restored. Changed binding or missing required history refuses activation. |
| O3 runtime admission,queued after O2 | Financial developer owns new production financial module/tests; reads experimental financial kernel as reference. No wholesale copy of test harness. | Reviewed O2 and explicit domain/mapping decisions. Common durable order for capture/funding/repair; stable action identity/digest; identical retry returns original disposition, changed retry conflicts; balanced integer legs and once-only obligations. Generalize fixed buyer/seller assets with negative invariant tests. |
| O4 SQL/read seam,queued after O3 | Projection developer owns scoped migration, projector/repository/tests and existing settlement reads under lease. | Reviewed O3. One transaction for journal/accounts/obligations/exceptions/checkpoint; epoch/version fencing; crash-after-commit replay once; authenticated run/account scope; common snapshot rows/progress. No full-history rescan on hot path. |
| O5 end-to-end,queued after O4 | Integration developer owns fixture/finite launcher and integration tests; manager owns combined branch. | Reviewed O4. Insufficient resources→pending with zero partial legs; funding plus distinct repair attempt→settled once. Restore source/admission/owner/SQL cuts; compare exact API rows/progress and journal. Record finite stage costs, no capacity upgrade. |

O0 must pin numeric limits from small deterministic fixtures and current resource
profile before O1/O2 implementation. No invented accepted SLO. Launcher-only caps
cannot establish finite end-to-end budget: alternate manual/API/stream inputs must
be enforced or explicitly fenced from isolated run. No `202 Accepted` before
configured durable ingress acceptance. Preserve same-lane ordering and replay;
avoid new synchronous hot-path DB writes/scans without evidence. If enforceable
design needs heavy pivot, spike and return human gate; fixture proof remains scoped.

Keep `calcify-finite-source-v1` canonical bytes/hash and existing snapshot fixtures
unchanged. New budgets use separate versioned contract/history binding or explicit
approved migration; never silently append v1 fields. Bind source profile, budget
version/digest, run/lane/topic generation/UUID, acceptance accounting and replay cut
durably before continuing history. Restore/refetch with changed binding must refuse;
exact canonical replay uses frozen config. Accounting design declares which refused
attempts generate durable history, includes those bytes in bound, and prevents
retry/rejection flood from exhausting reserved closure. Tests target enforcement
before and after acceptance crashes, not merely finite launcher behavior.

Dispatch O3→O4→O5 immediately as reviewed predecessors permit, preserving finish
reserve. No automatic next-session deferral; no adoption inferred from implementation.
If time expires, stop at largest completely reviewed dependency prefix and report
remaining finite P3 gates. Never waive review to reach last queue item.

## Eight hour operating window

| Elapsed time | Work | Gate |
| --- | --- | --- |
| 00:00–00:45 | O0 alignment; docs reconciliation in parallel; tool/resource preflight | Manager freezes scope, budgets and leases |
| 00:45–02:45 | O1 implement, self-audit, focused tests | Frozen candidate and author packets |
| 02:45–03:30 | Independent O1 review; corrections if bounded | Manager accepts O1 or starts recovery spike |
| 03:30–05:30 | O2 implement, self-audit, adverse replay tests | Frozen candidate and author packets |
| 05:30–06:15 | Independent O2 review and corrections | Manager accepts O2 or preserves partial checkpoint |
| 06:15–07:15 | Advance O3→O4→O5 when predecessor gates and remaining time permit; otherwise finish reviewed prefix integration | No dependent code before upstream gate; no deadline waiver |
| 07:15–08:00 | Final integration, owner docs/evidence/retention, handoff | Stop new features; honest morning report |

Schedule is pacing guide, not fixed waits: early completed gates release next P3
unit immediately. Enforceable O2 may exceed120min; narrow checkpoint honestly,
retain ingress-budget gate as open and prohibit dependent live qualification.
Timeboxes prioritize; they do not waive tests/reviews. At30min without new evidence,
manager asks for blocker report and triggers spike if scope/knowledge failure.
Keep45min finish reserve; overnight timeout returns incomplete units as incomplete.
No dependency-heavy work scheduled merely to occupy every developer slot.

## Manager orchestration

This chat owns requirements, dispatch, file leases, integration order, resource
leases and acceptance. Developers own implementation, internal audit/tests and
review initiation. Independent reviewer owns read-only judgment. Manager receives
review report and developer reconciliation before accepting task.

Available concurrency: four agents including manager. Default allocation:
manager + one implementation developer + one docs/research developer + one fresh
reviewer. All subagents share filesystem; isolation is explicit file ownership,
not automatic branch separation. One Git writer at a time; manager serializes
branch/commit/integration operations. Separate worktrees only when needed and
created/attached explicitly with unique branches; [official worktree guidance](https://learn.chatgpt.com/docs/environments/git-worktrees)
explains isolated checkouts. No unrelated edits reverted.

Use internal agents for subtasks. Separate sidebar developer chats only if user
explicitly requests them; create project/worktree chat, record real returned ID,
authorize manager follow-up prompts, and use bounded wait snapshots for progress.
Independent reviewer gets fresh task with no implementation conversation inherited.
No fork of developer chat. Keep neutral bootstrap separate from author rationale.

Record ledger at launch under `docs/work/` or `.planning/`; WORK_PLAN remains
single canonical execution board. Each row carries unit ID, state, owner/agent ID,
base/head, dirty files, file leases, dependency verdicts, tests/evidence, blocker,
next action, review instance count, spike count and resource ownership. Persist on
every handoff and before compaction. Resume by reading ledger and verifying Git,
not by dispatching duplicate workers.

States:

`QUEUED → ALIGNED → IMPLEMENTING → SELF_CHECK → FROZEN → INDEPENDENT_REVIEW → FIXING/READY → MANAGER_ACCEPTED → INTEGRATED`.

Failure path:

`scope/knowledge/flow failure → RESEARCH_SPIKE → REPLANNED → ALIGNED`;
unresolved owner decision, heavy pivot or exhausted review budget→`HUMAN_GATE`.

Worker reports useful evidence/milestone updates at most30min apart. Manager
monitors returns and blocked work without repeated unchanged chatter. Completion
requires observable gates, not developer confidence or passing test count alone.

## Per developer completion and independent review

1. Verify baseline, task contract, dependencies and leases; read applicable AGENTS,
   steering and current owners. Establish affected calls with graph/coverage and
   source fallback when stale. Ask manager about evidence conflicts before coding.
2. Implement smallest accepted scope with behavioral tests, contracts and docs.
   Internal audit checks ordering, durable acceptance, idempotency, financial
   balance, invalid input, rollback/failure, restore and compatibility as relevant.
3. Run exact affected checks; retain commands, exit codes, environment, source/build
   identity, red/green regressions and limitations. Never mark unrun check passed.
4. Freeze base/head or explicit dirty diff with hashes. Produce neutral bootstrap
   and separate author explanation using installed `independent-review` skill.
   Resolve actual installed `SKILL.md` locally; keep bootstrap separately readable.
5. Initiate fresh independent reviewer with bootstrap only. Reviewer checks code,
   tests and plan, records preliminary concerns, then reads author explanation.
   Read-only review; no edits, staging, self-dispatch or scope expansion.
6. Reviewer returns `Ready`, `Ready with non-blocking follow-ups`, `Not ready` or
   `Unable to verify`, evidence and `Review instance N of3`. Developer answers each
   finding `Accept`, `Dispute with evidence` or `Defer with owner and rationale`.
7. Correct real defects and rerun affected tests. Material changes require another
   fresh independent pass. Default maximum3 instances per delivery unit, including
   first pass; follow-up review and research do not reset count. Manager refuses
   splitting unit merely to evade review cap. Cap reached→human decision.
8. Return report plus developer response, frozen candidate, test receipts,
   contracts/docs/retention outcome, remaining risks and dependencies to manager.
   Manager accepts only complete scoped criteria; real P0/P1/P2 defects block.
   Non-blocking follow-ups require named owner and no missing required gate.
9. Manager integrates serially. Combined behavior/plan independently reviewed under
   separate declared integration unit, also max3; earlier unit reviews do not sign
   off integration changes. Any later material fix invalidates affected verdict.

Heavy architectural pivot, new workstreams or substantial scope expansion follows
installed independent-review skill's human gate. Research can inform proposal;
cannot substitute for required owner approval. Overnight absence leaves such unit
blocked while unrelated authorized work continues.

## Mandatory research spike on flow failure

Trigger when developer cannot meet scope/constraints, two implementation approaches
fail,30min passes without new evidence, review exposes missing domain knowledge or
scope cannot be verified. Ordinary first failing regression stays normal developer
loop; repeated inability or invariant mismatch becomes spike. Preserve failed diff,
test output and evidence before changing approach.

Same developer or fresh research agent gets45–60min, read-only by default, and
specific question. Required output: reproduced failure, requirement/code/evidence
map, failed assumptions, current source/contract/Git alignment, minimal options,
recommended bounded fix or smaller scope, risks, test plan and explicit return gate.
Use current source first; relevant Records originals only for historical questions.
No speculative rewrite or resource escalation as substitute for understanding.

Manager validates research against canonical owner and dependency gates, updates
scope/acceptance packet, then assigns same or fresh developer. Replacement receives
failed evidence and remaining review count. One recovery spike/replan per unit
proposed overnight; second flow failure parks unit with findings and decision request.
Do not hide failure, reset reviewer cap, silently drop acceptance or recurse forever.

## Verification and resource policy

Preflight tools/JDK/Go/protobuf/Docker availability and writable caches. Use
repository local config; do not overwrite existing `.env`. Local code/docs checks
start before any broker load. One manager-owned broker/resource lease; unique run,
topic, application and evidence IDs. Shared stack reset/volume deletion, larger
resource profile and live financial adoption require explicit session authorization.
Earlier8GiB diagnostic permission does not create blanket permission for new loads.

Examples of existing checks, selected according to changed behavior:

```sh
git diff --check
bun scripts/ci/check-records-retention.mjs
node --test scripts/dev/calcify-financial/gate-model.test.mjs
./services/platform-runtime/gradlew --no-daemon -p services/platform-runtime test --tests 'com.reef.platform.calcify.*'
./scripts/generate-proto.sh
```

Matching changes additionally run from `services/matching-engine`:

```sh
go test ./...
go test -race ./internal/app ./internal/streamdirect
go vet ./...
```

Generated contract drift checked after regeneration with pinned documented tools.
O1/O2 negative acceptance must cover zero-trade members, missing/duplicate/changed
members, multi-fill ordinals, offset gaps, cross-run identity, replay batch variation,
future-window flood at high watermark, closure under pause, crash/restore cut,
manual/API/stream budget bypass, retry/rejection flood, changed durable binding,
restored accounting, unchanged v1 digest fixtures and unavailable history.
Broker doubles/model tests establish their limited scope;
actual transaction/restart claims require real isolated broker proof on same source.
If unavailable, dependent live claims stay open rather than marked passed.

No throughput campaign needed for source correctness slice. If performance work
later selected, freeze workload/hardware/config/age/observers/budgets and retain
every attempt/correction; report accepted, matching, resolved, financial, SQL/API
stages separately. Never compare D7 and financial diagnostic as same pipeline.

## Completion and morning handoff

Each code unit completes [delivery policy](../ENGINEERING_DELIVERY_POLICY.md) and
[retention pass](../RECORDS_RETENTION.md#required-completion-pass): affected tests,
contracts, owner docs, latest evidence, guidance/map/board review. Promote still-live
facts before archiving superseded standalone records; publish/verify Records first,
then remove local copies and repair immutable links. If archive access unavailable,
keep originals and mark retention incomplete. No fabricated archive commit.

Morning handoff lists integrated units and exact source, independent verdicts/counts,
tests actually run, skipped gates, failures/spikes and retained evidence, resources
stopped/volumes preserved, adoption boundaries, branch/PR metadata and next dependency.
No merge or deployment implied by manager acceptance. Capacity and financial
authority statements retain measured scope.

Planning delivery retention: new active plan/review only; no code/contract/runtime
change or superseded record removed. Archive pass no-op. Existing doc drift is
explicit O0 task, not silently corrected historical evidence. No prior benchmark
rerun needed for planning prose.

Planning review record: instance1of3 `Not ready` identified ingress-budget bypass,
missing durable binding/v1 compatibility and downstream priority drift. All three
accepted and corrected above. Fresh instance2of3 `Ready`, no actionable findings,
on plan content SHA256
`9e11660a44573a0ead8561266881ae4069077d8c20cb46773aad34723e096267`
before this publication annotation. Independence limit: second bootstrap disclosed
prior findings; reviewer verified current plan/owners independently without author
explanation. Verdict covers planning preparation, not implementation or qualification.
Local links/whitespace and retention520 passed. O0 still freezes actual numeric
budgets, enforcement design, resource profile and leases at launch. No instance3
needed for this annotation; future material plan changes require fresh review.

## Dispatch prompt template

```text
Implement unit <ID> from CALCIFY_OVERNIGHT_SESSION_PLAN.md at <exact base>.
Objective/acceptance: <observable criteria>. Dependencies: <accepted verdicts>.
Own only <paths>; shared-file lease <paths or none>. You are not alone; preserve
others' work and adapt to concurrent changes. No Git mutation without manager lease.
Read AGENTS and task-relevant owners; verify actual source instead of old handoffs.
Budgets/exclusions: <finite scope, resources, time, adoption boundary>.
Build, audit internally, test behavior/errors/replay/compatibility, update owners.
Freeze scope; prepare neutral bootstrap and separate author explanation.
Initiate fresh read-only independent-review, instance1of3; reviewer gets no inherited
implementation history. Return each report before next instance. Material fixes
need fresh review; max3 total, no resets through replacement agents or spikes.
On inability to complete scope/constraints, stop speculative coding, preserve
failure, conduct or request bounded research spike, realign with manager, then retry.
Heavy pivot or exhausted cap returns human decision gate. Return reviewed candidate,
acceptance mapping, test/evidence receipts, retention outcome and residual risks.
```
