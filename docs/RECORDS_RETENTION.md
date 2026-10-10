# Records retention

Policy updated October 3, 2026: Reef keeps current code/docs, executable fixtures and concise focused verification summaries. Complete bulk evidence—including latest raw attempts, failures, provenance and reviews—lives in [Reef Records](https://github.com/dills122/reef-records), linked from Reef at immutable commits. Existing retained selections stay in place until individually verified migrations; no age-based sweep.

## Required completion pass

Apply to every feature, fix, refactor and code-working session before PR/handoff.

1. Update implementation, focused tests, affected contracts and owner docs together. Preserve latest relevant evidence with exact source/configuration, commands, date, scope, results and corrections. Generate new evidence when behavior or claims require it; do not rerun unrelated qualifications for prose-only changes.
2. Check guidance and overviews against resulting system: `AGENTS.md`, `AI_CONTEXT.md`, documentation map, relevant steering, system/project overview, technical design and accepted decisions. Update affected owners only; explain non-applicability in PR rather than editing every overview mechanically. Put current execution/status in `WORK_PLAN.md`.
3. Review touched topic and related plans, reports, handoffs, research and evidence for supersession. Keep only material needed for active planning, session understanding, onboarding, current system/design, operations or focused latest verification with immutable links to complete proof. File age or recent creation alone is not a reason to retain it. Before moving mixed current/history material, promote still-live facts and open tasks to current owner, leaving concise links to historical reasoning.
4. Lift and shift bulk evidence and superseded records into `reef-records` using [archive protocol](https://github.com/dills122/reef-records/blob/main/docs/ARCHIVE_POLICY.md): preserve bytes and paths, record source commit/hash/selection reason, publish and land archive, verify destination, then remove Reef copies and repair active links to immutable archive commits. Preserve latest complete bundle per topic/profile in Records, including failed latest attempts and corrections; keep executable fixtures and concise current summary in Reef. Never keep only a favorable success or remove active dependencies to meet size targets.
5. Update archive index and Reef relocation/retained-evidence inventory for actual moves; update relevant local navigation and reading guidance. Run retention checks, affected validation and link checks. PR/handoff records retained latest bundle, archive commit/PR and verification, or explicit no-op reason when no superseded material exists. Pass is required; artificial archive churn is not.

Checker loads every `docs/records/*-migration.json`, preserving historical relocation facts and each pinned archive commit. [`retained-evidence.json`](records/retained-evidence.json) owns current latest-bundle selection; retained lists embedded in older migration manifests remain historical snapshots. Update maintained inventory with dated replacement/archive reasons whenever bundles change. An unchecked inventory does not complete retention gate.

Ordinary prior versions of continuously maintained code/docs stay in Git history. Archive standalone superseded records and evidence; do not snapshot whole repository for every edit. No automatic age-based purge or deletion of ignored local data.

## Historical context lookup

Start sessions with current task, source/tests, `AI_CONTEXT.md` and relevant owner docs. Read Records when question needs historical reasoning, old run comparison, failed attempt, correction or superseded design; throughput baseline comparison remains mandatory when applicable.

Search [Records index](https://github.com/dills122/reef-records/blob/main/INDEX.md) and manifests by original path, topic, date, run ID or source commit. Open only relevant originals at recorded archive commit; parse evidence and corrections together. Cite archive path/commit, original source commit and measurement/design scope. Verify any claimed current implication against current source, contracts, decisions and latest evidence. Archive availability or an unchecked old checklist never establishes present behavior or open work.

If current work needs a durable lesson, add concise verified fact to current owner with archive citation. Keep original report and bulk historical narrative in Records. When history is unavailable, state missing evidence and its impact; do not guess or recreate its claims as current facts.

## Current retained selections

- Current code, tests, scenario definitions, contracts, accepted decisions, steering and operational runbooks.
- Active execution in `WORK_PLAN.md`, Calcify overview/discovery/current Phase 2 implementation plan and current handoff; latest October 1 architecture reviews.
- Latest Phase 1 evidence for 300-pair pacing, corrected 5k and 7.5k runs with reconciliation companions, and 10k calibration.
- Phase 2 implementation: latest corrected broker fault cohort `broker-a9bc0494`; latest multi-shape short cohort `capacity-0ccfefa6`; last candidate `capacity-156d616c` and last producer-batch comparison `capacity-4c565d6d`; latest sustained attempt `sustained-8ea6c8ce`; latest large recovery `recovery-5545850c`; full-path and nightly-checkpoint bundles. Latest failed attempts remain failures; retention does not upgrade their qualification.
- Latest master Calcify direct-throughput and joined-10k campaign bundles, including failed attempts, corrections, source manifests and current handoff. These are separate profiles from earlier phase diagnostics; campaign outcomes retain their recorded scope.
- Complete terminal-retention red/green proof and latest PR review pass2.
- [Runtime order identity](evidence/runtime-order-identity/README.md): final R08 local/real-DB/reviewer verification and CI follow-up with correction companions.
- Source fixtures consumed by current scripts, original checksum/provenance manifests and correction notes. These are required companions, not obsolete bulk.

Review retention when adding new evidence. Publish and verify complete bundles, including latest and older success/failure history, in Records before removing local bulk. A current contract or unresolved active plan does not become historical because its filename is old. Imported original Markdown remains unchanged; its relative links refer to source checkout at recorded source commit.

## October 2 migration

386 files, 39.36 MiB moved from Reef source commit `f8d905a83966658ade3723d1ded3b36cb468e223`. [Pinned archive index](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/INDEX.md) links every archived path and original context. [Relocation inventory](records/2026-10-02-migration.json) records hashes, byte counts, source blob IDs and reasons. Active links pin archive commit `ecd00e479853bcb9fd842b33f017289f37499226`.

`docs/archive/README.md` remains a small navigation pointer. September 4 `CURRENT_STATUS.md`, completed discovery/implementation reports, prior research, July run reports, projection-era plans and older probe cohorts are historical Records entries. Current execution remains in `WORK_PLAN.md`.

Original `SHA256SUMS` and JSON manifests may name paths now archived. Resolve those paths through relocation inventory rather than rewriting recorded measurements or hashes. Offline checker validates local checksum entries; archived entries resolve through inventory. Use optional archive checkout check to validate pinned destination blobs.

## October 2 second pass

After syncing `master` to `76872e9db249152e0329393d73c4a2f850ea2b93`, 37 originals (15 Markdown files; 179,521 bytes) published in [Records PR #4](https://github.com/dills122/reef-records/pull/4), archive commit `6b838e5287c5428c914f6577ff176c1cd03c44ec`. [Second inventory](records/2026-10-02-second-pass-migration.json) covers completed R08 task history, closed Calcify campaign scratch plans, superseded September 30 handoff and dated OCR pilot evidence.

Latest complete R08 verification rehomed under `docs/evidence/runtime-order-identity/` with exact-byte provenance. Active Calcify reports, plans and bundles remain local. Original Phase 2 evidence README archived before repairing local navigation; original `SHA256SUMS` resolves historical bytes through relocation inventory. Current pilot facts promoted into [CI Operations](CI_OPERATIONS.md#opt-in-opencodereview-pilot).

## Verify and restore

```sh
bun scripts/ci/check-records-retention.mjs
bun scripts/ci/check-records-retention.mjs --records-dir /absolute/path/to/reef-records
```

First command checks relocation metadata, removal/reference consistency, required fixtures and retained checksum companions. Second also verifies every destination blob at pinned archive commit and every SHA-256. CI runs offline check; no cross-repository credential required.

To restore one file, read its `archive_path` at pinned archive commit with `git show`, verify SHA-256 against inventory, then write original `source_path` on a feature branch. Preserve immutable archive and inventory. Update retention/index links for intentional restoration; do not silently restore entire historical tree.

Source deletion reduces current checkout size. Old Reef Git history still contains original blobs; this migration does not rewrite history. Ignored local runs, caches and build output are outside tracked migration inventory.

## October 3 Calcify E0 split

User-directed evidence split:97 original E0 files preserved at [Records08a4347](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/README.md),
[PR #5](https://github.com/dills122/reef-records/pull/5). Verified exact bytes before
removal. [Relocation inventory](records/2026-10-03-calcify-e0-migration.json) pins
source/archive hashes; focused [checkpoint](evidence/calcify-financial-sprint1/README.md),
frozen inputs and broker fixture remain local. No readiness upgrade or other-topic
archive sweep. Future completion follows this compact-summary/full-proof split.


## October6 Calcify empirical sprint exit

[Retention inventory](records/2026-10-06-calcify-sprint1-exit-retention.json) keeps
current sprint owner, RFC, documentation map and concise latest verification in
Reef. [Closed raw proof](https://github.com/dills122/reef-records/tree/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-2026-10-06-1c143125049d) plus [independent delivery review](https://github.com/dills122/reef-records/blob/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-review-2026-10-06-1c143125049d/reviews/delivery1/report.md)
published at `2331d97d9f9e6af8638d6ed335adbfac743d2c03`;7628files/1144093245B verified against source and remote
commit/tree binding. All three input journals reassemble exactly from32MiB chunks.
Original setup refusals, two4GiB resource-aborted arms,8GiB target miss/full-cohort
parity and correction/review receipts preserved. No runtime authority or
conservative capacity qualification follows.

Source checkpoints remain local-only, not pushed to Reef. RocksDB/build/deps and
retained broker output-topic volumes excluded; complete raw output replay is not
claimed. No tracked Reef original removed; [Records PR10](https://github.com/dills122/reef-records/pull/10)
was draft at October6 checkpoint; merged October7 at `1419b7e0`.
Archive landing supersedes original local-retention-until-landing tasking; no
local cleanup performed by this documentation correction. Guidance and
overviews unchanged for test-only resource permission; affected owners updated.
