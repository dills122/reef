# Records retention

Policy confirmed October 2, 2026: Reef keeps current code and docs plus latest complete evidence bundle per topic/profile. Historical documents, older attempts and dated reports live in [Reef Records](https://github.com/dills122/reef-records).

## What stays

- Current code, tests, scenario definitions, contracts, accepted decisions, steering and operational runbooks.
- Active execution in `WORK_PLAN.md`, Calcify overview/discovery/current Phase 2 implementation plan and current handoff; latest October 1 architecture reviews.
- Latest Phase 1 evidence for 300-pair pacing, corrected 5k and 7.5k runs with reconciliation companions, and 10k calibration.
- Phase 2 implementation: latest corrected broker fault cohort `broker-a9bc0494`; latest multi-shape short cohort `capacity-0ccfefa6`; last candidate `capacity-156d616c` and last producer-batch comparison `capacity-4c565d6d`; latest sustained attempt `sustained-8ea6c8ce`; latest large recovery `recovery-5545850c`; full-path and nightly-checkpoint bundles. Latest failed attempts remain failures; retention does not upgrade their qualification.
- Complete terminal-retention red/green proof and latest PR review pass2.
- Source fixtures consumed by current scripts, original checksum/provenance manifests and correction notes. These are required companions, not obsolete bulk.

Review retention when adding new evidence. Preserve complete bundles and older success/failure history in Records before replacing a local bundle. A current contract or unresolved active plan does not become historical because its filename is old. Imported original Markdown remains unchanged; its relative links refer to source checkout at recorded source commit.

## October 2 migration

386 files, 39.36 MiB moved from Reef source commit `f8d905a83966658ade3723d1ded3b36cb468e223`. [Pinned archive index](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/INDEX.md) links every archived path and original context. [Relocation inventory](records/2026-10-02-migration.json) records hashes, byte counts, source blob IDs and reasons. Active links pin archive commit `ecd00e479853bcb9fd842b33f017289f37499226`.

`docs/archive/README.md` remains a small navigation pointer. September 4 `CURRENT_STATUS.md`, completed discovery/implementation reports, prior research, July run reports, projection-era plans and older probe cohorts are historical Records entries. Current execution remains in `WORK_PLAN.md`.

Original `SHA256SUMS` and JSON manifests may name paths now archived. Resolve those paths through relocation inventory rather than rewriting recorded measurements or hashes. Offline checker validates local checksum entries; archived entries resolve through inventory. Use optional archive checkout check to validate pinned destination blobs.

## Verify and restore

```sh
bun scripts/ci/check-records-retention.mjs
bun scripts/ci/check-records-retention.mjs --records-dir /absolute/path/to/reef-records
```

First command checks relocation metadata, removal/reference consistency, required fixtures and retained checksum companions. Second also verifies every destination blob at pinned archive commit and every SHA-256. CI runs offline check; no cross-repository credential required.

To restore one file, read its `archive_path` at pinned archive commit with `git show`, verify SHA-256 against inventory, then write original `source_path` on a feature branch. Preserve immutable archive and inventory. Update retention/index links for intentional restoration; do not silently restore entire historical tree.

Source deletion reduces current checkout size. Old Reef Git history still contains original blobs; this migration does not rewrite history. Ignored local runs, caches and build output are outside tracked migration inventory.
