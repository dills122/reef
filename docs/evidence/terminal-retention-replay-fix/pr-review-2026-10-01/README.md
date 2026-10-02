# PR #438 review and master integration

Original PR head `9778e847`; merged master `92cdd20b` (PR #436 run-scoped order identity, #435 CI and #437 research). Original evidence remains unchanged. No JSON treatment or load run.

OCR comments reviewed against actual call paths:

- `4162398210`, nil-owned reservation leak: not reproduced. Non-batch eviction calls `release`; only non-nil batches call `releaseInBatch`. Commit and rollback release reservations owned by that batch.
- `4162398217`, rollback ordering: suggested early release would remove protection before index restoration. Retention rollback changes heap only; per-book rollback precedes one first-preimage restoration per run/order identity, then reservations release. Keep protected restoration ordering.
- `4162398226`, nil rollback panic: `trackOrderRecord` checks nil receiver before dereference. Nil method call is safe. Guard call site for clarity without changing behavior.

Merge must retain both lane-local immediate retention/V4 compatibility and master run-scoped identity. Reservations and first-preimage journal use `(runId, orderId)`; per-book orders remain keyed by orderId within exact book. New regressions cover same IDs in different runs, eviction/commit/rollback and snapshots, plus non-batch eviction/reuse. Focused app/streamdirect tests pass. Final `go test -race ./... -count=1` passes all matcher packages, exit0, after source freeze. [Race log](green-race-all.log) and [manifest](manifest.json) retain exact source/log hashes and timing; independent source review clear. No critical correctness finding.

Targeted regression cases cover different-run identical IDs through provisional eviction/other-run commit/rollback/V4restore; distinct first preimages and new-ID deletion in one multi-run batch; direct non-batch terminal eviction followed by reuse; existing same-run cross-book provisional reservation and rollback.
