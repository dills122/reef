# Post-match ledger execution index — 2026-09-28

Status: active, first slice ready. Branch: `codex/ledger-authority-redesign`.
Integration destination: this branch, then a reviewed PR. Lead owns integration,
proof reconciliation, and the final cutover recommendation.

## Objective and boundary

Deliver a correct, replayable, auditable post-match settlement path whose
financial authority is a durable typed journal, without changing matching,
ingress acceptance, command ordering, or D-058 outcome format. Preserve live
market-data API response and freshness under settlement load as a **cutover
gate**, not a follow-up. Committed matching outcomes feed sibling market-data
and settlement consumers; trade tape may show execution while settlement is
pending and must retain it if settlement fails. This index
coordinates delivery; the product contract and raw evidence remain in
[`SETTLEMENT_LEDGER_JOURNAL_CONTRACT_2026-09-28.md`](SETTLEMENT_LEDGER_JOURNAL_CONTRACT_2026-09-28.md)
and [`settlement-journal-write-shape-2026-09-28.md`](../research/evidence/settlement-journal-write-shape-2026-09-28.md).
The agreed authority split is D-060 in [`DECISIONS.md`](../DECISIONS.md).
The logged write-shape test is only a component proof, not a capacity pass.

No production deployment or irreversible migration is authorized. Pre-release
breaking storage/API changes are allowed only after the complete proof and an
explicit decision; document them without compatibility dual-write.

## Work items

| ID | Outcome | Depends on | Delivery unit / owner | Status |
| --- | --- | --- | --- | --- |
| PMJ-00 | Record agreed authority, flow, failure and market-data cutover contract | None | Lead | Done in D-060 and settlement contract; not implementation |
| PMJ-01 | Exact versioned source and control inputs; independent reference interpreter and parity/fault fixtures | None | Internal worker; lead integrates, independent reviewer checks | Reference proof ready for user review; authority adapters remain for vertical proof |
| PMJ-02 | Fenced, atomic typed journal append and ordered in-memory evaluator, default-off | PMJ-01 | Internal worker; lead integrates | Waiting |
| PMJ-03 | Snapshot/replay, ambiguous commit, takeover and independent-restore fault proof | PMJ-02 | Internal worker; lead integrates | Waiting |
| PMJ-04 | Rebuildable settlement projections, as-of reads, independent market-data checkpoint and explicit trade-tape freshness frontier | PMJ-02 | Internal worker; may run alongside PMJ-03 only with disjoint files | Waiting |
| PMJ-05 | Complete sustained correctness/capacity run for both sibling paths and measured decision | PMJ-03, PMJ-04 | Lead + bounded research/test worker | Waiting |
| PMJ-06 | Independent architecture/code review and conditional breaking cutover plan | PMJ-05 pass | Independent reviewer; lead decides | Waiting |

PMJ-01 through PMJ-04 do not authorize retiring the current settlement worker
or public API cutover. PMJ-06 proposes adoption only if PMJ-05 passes; otherwise
stop this PostgreSQL candidate and decide from the failing stage rather than
micro-tuning. The lead will integrate incrementally and stop for a user review
after PMJ-01 before authorizing PMJ-02.

The first real vertical proof must include both sibling consumers under load,
even if PMJ-04 completes final market-data read contracts later. Do not call
the journal path integrated while book/depth/tape freshness is unmeasured.
Matching and pre-trade remain outside every item in this index.

## Acceptance and evidence

- PMJ-01: fixed hot, diverse, empty, break, retry, duplicate and changed-input
  fixtures prove deterministic parity or explicitly classified divergence.
  Record focused test commands and exact outcomes.
- PMJ-02: commit atomically publishes one ordered result set and head advance;
  rollback publishes none. Measure source read, evaluation, serialization,
  head wait, append/commit and WAL/index bytes separately.
- PMJ-03: crash before/after append, lost reply, stale owner, changed bytes,
  source-only/target-only restore and corruption cannot double-post or silently
  change scarce-account order. Unsafe recovery fails closed.
- PMJ-04: settlement reads expose journal frontier and never present stale
  balance as current. Book, depth and trade tape remain matching-outcome facts;
  a pending or failed settlement never erases execution. Market reads use
  bounded indexed projections, never a settlement-journal scan or per-request
  book rebuild. Checkpoints and connection budgets are independent.
- PMJ-05: fixed local 10k accepted-commands/s, 300-second run with exact
  closed-cohort reconciliation. All source, journal and projection stages keep
  up; backlog does not merely drain after source stops. Compare 640-trade hot
  chain with 145.45 ms workload budget and 121.21 ms headroom target. Preserve
  every raw run, failed run and correction with code/config/workload metadata.
  Freeze separate numeric market API p95/p99 response and matching-outcome-to-
  visible age limits before qualification. Measure book/depth/tape latency and
  freshness, settlement backlog, projection lag, shared source pressure and DB
  saturation in that same run. Either path falling behind fails cutover.
- PMJ-06: independent review, decision, updated contracts/tests/ADRs/operator
  docs and PR evidence. No production action is included.

## Verification ledger

| Date | Item | Evidence | Result |
| --- | --- | --- | --- |
| 2026-09-28 | Pre-work journal write shape | Linked raw evidence and focused PostgreSQL test | 640-result append samples 28.53–31.39 ms in final warm run; not end-to-end |
| 2026-09-28 | PMJ-01 reference and normalized-path parity | `ReferenceSettlementInterpreterTest` and `SettlementJournalReferenceParityIntegrationTest`; combined offline Gradle run against disposable `reef_journal_parity_20260928` settlement database | 9 reference tests and 1 PostgreSQL test; 0 skipped/failures/errors. Covers ordered scarce winners, full four-leg effects, breaks, explicit retry, empty ranges, duplicates, changed inputs and normalized-path parity for three small cohorts. Fixture proof callbacks do not authenticate source absence or control provenance. |

Focused command from `services/platform-runtime`, with
`SETTLEMENT_POSTGRES_PASSWORD_TEST` supplied by the local test environment:

```sh
SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef_journal_parity_20260928 SETTLEMENT_POSTGRES_USER_TEST=reef ./gradlew test --offline --console=plain --tests com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreterTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalReferenceParityIntegrationTest
```

Gradle: `BUILD SUCCESSFUL in 3s`. JUnit XML: reference `tests=9`, parity
`tests=1`, both `skipped=0`, `failures=0`, `errors=0`.

PMJ-01 binds source identity to the exact retained `result_payload::text` value
read from PostgreSQL JSONB. Original matching transport bytes are not retained
by the current source store. This is an explicit proof boundary, not a claim
that original wire bytes can be recovered. Before PMJ-02 integrates the real
source reader and control authority, decide whether retained JSONB text is the
required byte identity, implement authentic empty-range and ordered-control
proofs, and verify their restore behavior. The disposable parity database was
created because the existing local settlement database has a migration 0009
checksum mismatch; its migration history was not rewritten.

Each worker returns scoped status, files, exact commands/results, decisions,
assumptions, limitations and next action. Lead verifies material claims in
source and runs integration checks before marking an item complete.
