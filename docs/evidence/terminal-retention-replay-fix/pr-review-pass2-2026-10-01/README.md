# PR #438 second OCR pass

Reviewed head `997225b7`; sync master `24cdc1de` includes Kotlin-only #439. Matcher runtime unchanged. Independent source review finds all three claims non-actionable.

| OCR comment | Assessment and evidence |
| --- | --- |
| 4162556163: V3 retention auto-upgrade | Intentional safety guard. V3 metadata cannot establish per-book retention policy or recover previously evicted history. Restore permitted with disabled retention; canonical replay required to enable bounded model. New default-restore test covers unset, zero and positive environment limits without option override. |
| 4162556191: stale rollback heap index | Heap Swap writes both entry indices; Push/Pop set indices. Reverse undo restores later-evicted entries before removing earlier additions. Same-lane batch overlap violates exclusive ownership contract. Commit finalizes already-live changes; closed batches cannot roll back. New regression moves/evicts multiple entries, compares full pre-batch checksum and retained facts after rollback and V4 restore. No early-release, ignored rollback mutation or linear scan added. |
| 4162556205: helper tie nondeterminism | Helper returns only raw order-ID strings. Equal comparator keys produce identical output strings, regardless of lane/map iteration. Sorting a copied aggregate slice does not call heap Swap or alter entry indices. Current matcher search finds only three callers, all tests; snapshot code does not use this helper. Suggested index tie-break is not a lane identity. |

Structural lookup used reef-main graph generation2026-09-30T21:14:17Z as provisional evidence. Snapshot freshness missing and indexed root differs from PR worktree. Exact current source fallback across services/matching-engine verified helper references and snapshot behavior; no reindex launched.

New behavior coverage: `TestTerminalRetentionLegacySnapshotDefaultRestoreUsesConfiguredLimit` and `TestTerminalRetentionRollbackRestoresAfterMultipleHeapReorders`. These pass on existing safeguards; no bug claimed or synthetic red failure manufactured. Full `go test -race ./... -count=1` passes all matcher packages. [Raw race log](green-race-all.log), [run boundaries](run.json), [source/log hashes](manifest.json). No load/performance claim.
