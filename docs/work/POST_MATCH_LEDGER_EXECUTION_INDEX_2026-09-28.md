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
| PMJ-02 | Fenced, atomic typed journal append and ordered in-memory evaluator, default-off | PMJ-01 | Internal worker; lead integrates | Candidate core and focused proof in progress; external source/control proof open |
| PMJ-03 | Snapshot/replay, ambiguous commit, takeover and independent-restore fault proof | PMJ-02 | Internal worker; lead integrates | Recovery read and fault proof in progress; external restore anchor remains a gate |
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
| 2026-09-28 | PMJ-02 default-off core candidate, after authority-binding repair | `SettlementJournalControlCodecTest`, `SettlementJournalEvaluatorTest`, `SettlementJournalStoreIntegrationTest`, `SettlementJournalCandidateIntegrationTest`, plus PMJ-01 suites; combined offline Gradle run against disposable database | 29 tests, 0 skipped/failures/errors. Typed append, head fence, atomic rollback, duplicate/tampered-row rejection, ordered scarce winner, explicit retry and changed prepared-input rejection have focused coverage. Candidate reads retained JSONB outcomes through existing reader but uses fixture source/control proof callbacks. No projection, restore/replay, sibling market-data load, or throughput qualification yet. |
| 2026-09-28 | PMJ-03 local recovery-read and failure-boundary slice | `SettlementJournalVerifiedReadIntegrationTest` and `SettlementJournalFailureBoundaryIntegrationTest`, combined with prior six suites against disposable database | 35 tests total, 0 skipped/failures/errors. Verified primary-snapshot read rejects local row/chain/head corruption; immutable envelope and checked replay copy reject post-read mutation. Rollback, lost-reply duplicate and same-incarnation fence boundaries pass. No source/control authenticity, evaluator replay, snapshot, independent restore or new-incarnation takeover is claimed. |
| 2026-09-28 | PMJ-03 bounded genesis replay proof | `SettlementJournalReplayProofIntegrationTest` and extended `SettlementJournalCandidateIntegrationTest` against disposable database | 2 focused PostgreSQL tests, 0 skipped/failures/errors. Reads retained JSONB text again, rejects changed source value, decodes controls, compares typed results/four effects and cumulative state, and pins head. Fixture callbacks do not establish production control or empty-range authority; no snapshot, writer activation or independent restore. |

Focused command from `services/platform-runtime`, with
`SETTLEMENT_POSTGRES_PASSWORD_TEST` supplied by the local test environment:

```sh
SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef_journal_parity_20260928 SETTLEMENT_POSTGRES_USER_TEST=reef ./gradlew test --offline --console=plain --tests com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreterTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalReferenceParityIntegrationTest
```

Gradle: `BUILD SUCCESSFUL in 3s`. JUnit XML: reference `tests=9`, parity
`tests=1`, both `skipped=0`, `failures=0`, `errors=0`.

PMJ-01 binds source identity to the exact retained `result_payload::text` value
read from PostgreSQL JSONB, plus canonical source fields. This retained UTF-8
representation is the accepted settlement replay byte identity; original
matching transport bytes are not required or recoverable from the current
store. PMJ-03 must verify the same retained representation after restore and
fail closed on changed text or missing members. Authentic empty-range and
ordered-control proofs remain open. The disposable parity database was
created because the existing local settlement database has a migration 0009
checksum mismatch; its migration history was not rewritten.

PMJ-02 first diagnostic sample used a four-outcome/two-trade synthetic fixture,
fresh test JVM and uncommitted code before prepared-input and duplicate-metadata
binding repairs: source read 205.10 ms, evaluation 7.67 ms, proposal mapping
8.68 ms, serialization 0.76 ms, head wait 1.72 ms, append/commit 24.64 ms;
inclusive PostgreSQL WAL position advanced 11,824 bytes and journal indexes
grew 106,496 bytes (initial page allocation). These are one cold fixture sample,
not fixed-workload latency or capacity evidence. Raw output is retained in
`SettlementJournalCandidateIntegrationTest` JUnit XML when
`SETTLEMENT_JOURNAL_PROOF_BENCH=1`. The source/control proof callbacks and
cross-system restore checks remain open; no cutover claim follows from this
sample.

Raw diagnostic line from the default-off branch worktree, with disposable
`reef_journal_parity_20260928` database and
`SETTLEMENT_JOURNAL_PROOF_BENCH=1` (`--rerun-tasks`, local test password supplied
through environment):

```text
journal-candidate source_read_ns=205103833 evaluation_ns=7665084 mapping_ns=8684208 serialization_ns=759333 head_wait_ns=1721792 append_commit_ns=24637708 wal_bytes_inclusive=11824 index_bytes_delta=106496
```

Final focused command from `services/platform-runtime`, with disposable test
database and `SETTLEMENT_POSTGRES_PASSWORD_TEST` set locally:

```sh
SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef_journal_parity_20260928 SETTLEMENT_POSTGRES_USER_TEST=reef ./gradlew test --offline --console=plain --tests com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreterTest --tests com.reef.platform.application.settlementjournal.SettlementJournalControlCodecTest --tests com.reef.platform.application.settlementjournal.SettlementJournalEvaluatorTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalReferenceParityIntegrationTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalStoreIntegrationTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalCandidateIntegrationTest
```

Final run: `BUILD SUCCESSFUL in 4s`; JUnit XML 29 tests, zero skipped,
failures or errors. `bun test scripts/dev/db/migrate.test.mjs`: 26 pass,
zero fail. Reviewer-confirmed PMJ-02 fixes include complete decoded-trade
manifest consumption, exact prepared-input binding, durable duplicate-row
verification and control sequence/step ordering.

PMJ-03 authority audit (2026-09-28): existing source reader and verifier can
authenticate contiguous retained outcomes, including an outcome range with
zero trades; an empty SQL result is not an authenticated empty source-position
range. Current runtime policy assignments and admin profiles are mutable, and
resource openings are trigger-maintained from mutable positions. They do not
provide an immutable, globally ordered policy/opening/funding control log.
Recovery therefore keeps true empty-range and production control callbacks
fail-closed until those authorities are supplied. A journal database restored
behind acknowledged finality cannot fence itself: changed-incarnation takeover
also requires a durable monotonic anchor outside that restore domain.

PMJ-03 focused command from `services/platform-runtime`, with disposable test
database and `SETTLEMENT_POSTGRES_PASSWORD_TEST` set locally:

```sh
SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef_journal_parity_20260928 SETTLEMENT_POSTGRES_USER_TEST=reef ./gradlew test --offline --console=plain --tests com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreterTest --tests com.reef.platform.application.settlementjournal.SettlementJournalControlCodecTest --tests com.reef.platform.application.settlementjournal.SettlementJournalEvaluatorTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalReferenceParityIntegrationTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalStoreIntegrationTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalCandidateIntegrationTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalVerifiedReadIntegrationTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalFailureBoundaryIntegrationTest
```

Final local slice run: `BUILD SUCCESSFUL in 5s`; JUnit XML 35 tests, zero
skipped, failures or errors. This is a storage/reply boundary proof, not
financial-state replay or restore qualification.

Bounded replay command from `services/platform-runtime`, with same disposable
database and `SETTLEMENT_POSTGRES_PASSWORD_TEST` set locally:

```sh
SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef_journal_parity_20260928 SETTLEMENT_POSTGRES_USER_TEST=reef ./gradlew test --offline --console=plain --tests com.reef.platform.infrastructure.persistence.SettlementJournalReplayProofIntegrationTest --tests com.reef.platform.infrastructure.persistence.SettlementJournalCandidateIntegrationTest
```

Run: `BUILD SUCCESSFUL in 4s`; JUnit XML two tests, zero skipped, failures
or errors. Replay re-evaluates from genesis for each batch, so work grows
quadratically with batch count and is capped at 256 by default. It is a
bounded proof, not the snapshot-plus-tail restart path.

Each worker returns scoped status, files, exact commands/results, decisions,
assumptions, limitations and next action. Lead verifies material claims in
source and runs integration checks before marking an item complete.
