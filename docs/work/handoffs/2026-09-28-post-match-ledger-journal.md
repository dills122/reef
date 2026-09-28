# Handoff: post-match settlement journal redesign

## Objective And Boundary

Continue the agreed D-060 post-match-only redesign. Existing committed D-058
matching outcomes establish trades; a later ordered journal commit establishes
settlement. Market-data projection and settlement are independent sibling
consumers. Preserve deterministic replay, scarce-account winner order,
four-leg gross DvP, auditability, durable ingress acceptance, and real-time
market-data latency **and freshness**. Do not edit Go matching, ingress,
pre-trade, matching outcome format, or production deployment in this work.

Pre-release breaking read/storage changes are permitted when documented; do
not build a compatibility dual-write. A failed settlement attempt moves no
assets, leaves the trade executed and its obligation outstanding, and may be
followed only by an explicit ordered repair/funding attempt. Trade tape retains
the execution. The PostgreSQL journal is the first candidate to prove, not a
guaranteed production engine or qualified performance result.

## Canonical Sources

1. `AGENTS.md`, `docs/AI_CONTEXT.md`, and `docs/README.md` for current rules.
   Use codebase-memory and Serena for Reef; do not use CCE.
2. `docs/DECISIONS.md` D-058, D-059 and **D-060**, plus D-050 for the instant
   profile. D-060 records the new authority split but does not cut over D-059.
3. `docs/work/SETTLEMENT_LEDGER_JOURNAL_CONTRACT_2026-09-28.md` for the agreed
   end-to-end flow, financial journal contract, market-data gate and recovery.
4. `docs/work/POST_MATCH_LEDGER_EXECUTION_INDEX_2026-09-28.md` for PMJ-00..06,
   dependencies, acceptance and current status.
5. `docs/THROUGHPUT_BASELINES.md`, `docs/PERFORMANCE_LEARNINGS.md`, the original
   PM-S3 artifacts, and
   `docs/research/evidence/settlement-journal-write-shape-2026-09-28.md` before
   any performance claim or benchmark change.

This handoff supersedes `docs/work/handoffs/2026-09-27-post-match-settlement.md`
for the new journal direction; that older handoff remains historical evidence.
The user-supplied PM10 handoff in the originating conversation describes an
earlier failed experimental branch, not the current implementation plan.

## Current Repository State

- Repository checkout for this work: the dedicated worktree on the branch below;
  a fresh task should locate its own checkout rather than rely on a host path.
- Branch: `codex/ledger-authority-redesign`, based locally on `origin/master`
  commit `646987cd` (`Document PM-S3 fault lines and PM10 architecture gates`).
  Recheck remote head before PR or rebase; this is not a claim about current
  GitHub master.
- The originating task's primary checkout had unrelated `.planning/` work.
  Do not switch, clean or commit another checkout as part of this task.
- This branch has no production-path change or cutover. The journal proof test
  and evidence, design contract, D-060, work-plan pointer, execution index and
  this handoff are the current package. Verify `git status` at resumption;
  this handoff may be updated with a commit/PR after packaging.
- OpenRig was stopped at the user's request; its daemon is off and its
  generated repo scaffold was removed. Use the repository's
  `orchestrated-delivery` skill for any delegated work. An interrupted PMJ-01
  worker made no retained source changes at the last status check.

## Completed Work And Evidence

- PM-S3 accepted 2,776,548 commands in 300 seconds (9,254.07/s) but settled
  only 2.06–2.56 forty-trade windows/s against about 110 windows/s needed at
  the frozen mix. Accepted throughput is not settled throughput. Its ingress
  p95 was 93 ms; it did not prove a 50 ms response target.
- Prior hot 640-trade execution took 415–566 ms against a 145.45 ms
  workload-derived batch budget (121.21 ms with 20% headroom). This is a batch
  timing, **not** single API latency. The carried-input stage also missed its
  separate 64 ms allowance. The old seal permitted a late child insert, so
  dropping validation is not a correctness-safe shortcut.
- The isolated logged PostgreSQL journal write-shape proof passed two focused
  tests with rollback, duplicate, stale-owner, digest/replay and tamper checks.
  Final six warm 640-result appends took 28.53–31.39 ms with about 990 KB WAL.
  It excludes real source/policy/funding binding, live projection, full restore
  and sustained input. It is **not** a 10k/s or end-to-end latency proof.
- The agreed flow, failure semantics, physical write reduction, sibling
  market-data path and pass/stop gates are now recorded in D-060 and the
  settlement contract. PMJ-00 is documentation complete, not implementation.

## Decisions And Rationale

- `202 Accepted` is durable ingress acceptance; matching outcome commit is
  trade finality; journal commit is settlement finality. Settlement never
  decides whether the matching result existed. D-050's same-tick happy path
  remains a target, not a condition for execution visibility.
- Replace repeated intake/obligation/admission/attempt/leg/checkpoint/read-
  model financial writes with one ordered typed journal authority plus
  independently checkpointed, rebuildable projections. Preserve all logical
  audit facts, exact source coverage, workflow bytes and intermediate states
  through deterministic reconstruction. Moving unchanged work to a queue does
  not solve sustained backlog.
- One ordered owner evaluates committed state in memory, then atomically
  appends a bounded typed batch and fenced head. A stale owner, replay, lost
  reply or restore may not create a second transfer or a different scarce-
  account winner. Ordered immutable policy/opening/funding inputs are required
  before public cutover; mutable first-consumer observation is insufficient.
- Matching outcomes independently feed book/depth/trade tape. Settlement
  backlog or failure does not stall or erase market execution facts. Market
  reads use bounded indexed projections, not journal scans or per-request book
  rebuilds. Private account reads disclose journal as-of position.
- Cutover requires both paths to pass the same sustained, closed-cohort run:
  settlement rate/backlog/recovery and book/depth/tape API p95/p99 plus source-
  to-visible age. Freeze numeric market limits before qualification. A fast
  insert or quick but stale API response is not a pass.

## Blockers And Limitations

- Exact public status enum/response shapes, market-data numeric latency and
  age limits, batch size/age bounds, immutable policy/funding input protocol,
  and external restore incarnation are not yet frozen. Do not invent them as
  already accepted. D-058's live route cutover also remains unqualified.
- The current D-059 implementation still owns settlement until a passing
  proof, independent review and explicit cutover. D-060 does not authorize
  deleting old workers/tables now. No complete source-to-journal-to-read path,
  300-second local 10k/s proof, hosted proof, PR/CI/OCR, or production run has
  been completed for this replacement.
- Do not revive the old PM10 micro-tuning, mutable input seal, or branch-wide
  experimental implementation as an implicit dependency. If this PostgreSQL
  candidate misses the full-path gate materially, stop and choose from the
  measured failing stage rather than tuning single-digit percentages.

## Immediate Next Actions

1. Verify worktree, branch, commit, dirty files, current `AGENTS.md`, D-060,
   contract and execution index. Confirm the focused journal proof still runs
   against local disposable PostgreSQL before building on it.
2. Implement PMJ-01: exact versioned source/control inputs and an independent
   interpreter. Compare hot/diverse/scarce, empty, break/retry and changed-
   input fixtures with current behavior. Keep default-off, outside the old
   worker. Stop and report if exact authority requires a matching change.
3. After PMJ-01 review, implement the fenced journal/evaluator, recovery and
   rebuildable reads in PMJ-02..04. Instrument the market-data sibling in the
   **first integrated vertical proof**, not after ledger qualification.
4. Run PMJ-05 local fixed 10k accepted-commands/s for 300 seconds with exact
   closed cohorts and both-path latency/freshness/backlog evidence. Obtain
   independent architecture/code review before PR; then CI/OCR and only then
   consider matched disposable hosted qualification. Never use production.
5. Only on a passing reviewed result, decide PMJ-06 breaking cutover, remove
   old shadow settlement writes, update D-050/D-059/API/operator contracts and
   measure other DB-heavy post-match consumers separately.

## Verification Commands

Run from the work checkout; record exact output and any correction in the run
ledger. Local Docker is available again but verify its state first.

```sh
git status --short --branch
git diff --check
cd services/platform-runtime && SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef SETTLEMENT_POSTGRES_USER_TEST=reef SETTLEMENT_JOURNAL_PROOF_BENCH=1 ./gradlew test --offline --console=plain --tests com.reef.platform.infrastructure.persistence.SettlementJournalWriteShapeProofTest
bun test scripts/dev/db/migrate.test.mjs
```

The listed PostgreSQL and migration commands are continuation checks, not a
claim that this handoff reran them. The recorded proof test passed before the
architecture-doc update; no integrated implementation exists to test yet.

## Delivery Metadata

- Date: 2026-09-28; base/checkpoint: local `origin/master` `646987cd`.
- Branch: `codex/ledger-authority-redesign`; integration destination: a
  reviewed PR from this branch. Commit, push and PR: not yet recorded here.
- Current package paths: `docs/DECISIONS.md`, `docs/README.md`,
  `docs/WORK_PLAN.md`, the contract and execution index above,
  `docs/research/evidence/settlement-journal-write-shape-2026-09-28.md`,
  `services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/SettlementJournalWriteShapeProofTest.kt`,
  and this handoff. Verify status before packaging.
