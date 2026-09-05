# Implementation Status Audit — 2026-09-04

## Scope And Evidence Rules

Checked the active work ladder against `master` at `cebbffc1`, merged history
from July through September, current source/tests, available local report
artifacts, and the existing state-shape branch. This is a dated evidence record;
[`WORK_PLAN.md`](./WORK_PLAN.md) remains the execution ladder.

Implemented, tested, merged, hosted, and promoted are separate states. Missing
local evidence means "not found in this audit", not proof that a run never
happened. No remote environment or live branch protection was changed or
revalidated. Existing user edits were preserved.

## Reconciliation Matrix

| Area / old planning implication | Actual implementation and evidence | Disposition / remaining work |
| --- | --- | --- |
| External bot admission/onboarding still pending | `noodle-ventures` smoke #307 and fixes #308/#310/#311/#313/#315/#316 merged July 22-23; project owner confirmed onboarding complete. [Completion record](./BOT_ARENA_RELEASE_READINESS.md#admission-and-onboarding-completion). | **Stale; complete.** Preserve regression checks, remove initial onboarding from backlog. |
| Cancel/modify ownership, deterministic rejection, protobuf compatibility still to build | #337 binds canonical mutation context, rejects malformed timestamps deterministically, and checks descriptors/generated source drift. Go regression suites passed during this audit session. | **Stale; merged.** Separate architecture-review sign-off was not found; code completion alone does not assert that sign-off. |
| Order/command read authorization broadly unfinished | `PlatformHttpServerBoundaryTest` already covers participant order/current/history/fill denial, authenticated token scope, command client/participant mismatch, and missing identity. Both HTTP adapters use the boundary helpers. | **Partly stale.** Do not rebuild these checks. Settlement scenario-read visibility is the concrete unresolved family below. |
| All `/api/v1` object reads have equivalent ownership enforcement | Settlement facts, obligations, ledger, exceptions, proof, and score routes call authentication/rate-limit checks, then query caller-selected `scenarioRunId`. The read gateway receives no caller principal and returns run-level data. | **Incomplete contract/enforcement.** Define run/participant/operator visibility, then enforce and test it in both adapters. This audit identifies the missing boundary; it does not assert a tested production exploit. |
| Internal gRPC security/health absent | Kotlin `EngineTransport.kt` supports TLS and mesh modes and rejects implicit/plaintext configuration outside local/single-host profiles. Go server already registers standard gRPC health. | **Partly stale.** Peer/service-principal authorization and deployment identity proof remain; Go server itself uses plain `grpc.NewServer()`, so client TLS options alone do not establish end-to-end mTLS. |
| Role-aware readiness still to add | `readinessJson()` lists enabled ingress, worker, materializer, canonical/lifecycle/market projectors and admin store. Tests cover healthy and degraded cases. | **Partly stale.** Several checks prove configuration only: lifecycle/market readiness is literal `true`, canonical readiness checks partitions, materializer readiness checks provider. Add operational progress/failure checks where needed. |
| Durable stream routing still instrument-only | `StreamCommandEnvelopeBuilder.partition` hashes `runId|venueSessionId|instrumentId`; envelope construction requires those fields. | **Stale for durable intake.** `PartitionedGrpcStreamEngineTransport` still hashes instrument for transport fan-out; distinguish that compatibility transport from durable ingress. |
| First instrumented one-maintainer remote A/B not yet run | August 21 artifacts contain four projector samples with exactly one lifecycle/market maintainer, nested statement capture, I/O and WAL timing. Current checker results below. | **Stale.** Short remote comparison happened. Address failed downstream drain evidence before sustained `5k` promotion. |
| Post-trade lifecycle/security repair/realistic pending still to implement | Allocation through novation, settlement attempts/legs/ledger, exceptions and repair are implemented. Tests include `realisticDefaultMaterializesObligationsWithoutStartingAttempts`, `instantProfileCreatesSecurityBreakWhenSellerSecurityIsUnavailable`, and `settlementSecurityRepairCommandReattemptsBrokenSettlement`. #305 additionally scoped repair/resolution by break. | **Code implemented.** Standalone live security-repair and realistic-pending reports were not found; these are evidence tasks, not initial feature tasks. |
| Arena policies/roster/scoring broadly unfinished | #300/#301 implement eligibility, roster/run binding and scoped fills. Checked-in July 21 three-policy manifest passes reconciliation with 30 scoped fills per policy, zero accounting gap, and crossed-book warnings. July 14 hosted `900s` Arena summary passes. | **Implemented with bounded evidence.** Full multi-seed corrected-policy campaign and two named `30m` rehearsals were not found. Existing hosted scoring proof must not be described as no hosted evidence. |
| Bounded-state benchmark needs a new implementation | Local `codex/throughput-state-shape-control` contains `f00dd590`; one commit ahead and 41 behind audited master. Its plan records implementation and pending smoke/hosted evidence. | **Implemented on unmerged branch.** Reconcile/review before duplicating or promoting it. |
| CI hardening / Arena application image selection still pending | #349 landed CI hardening. #314/#317/#318 implement immutable app deployment, SQL-only migration packaging and explicit profile services; deployer selects Arena runtime image. | **Merged.** Live configuration and ongoing workflow health remain operational checks, not initial implementation. |

## Concrete API Follow-Up

Evidence paths:

- [Boundary helpers](../services/platform-runtime/src/main/kotlin/com/reef/platform/api/ExternalApiBoundary.kt):
  `checkRead` authenticates/rate-limits; `checkParticipantRead` additionally
  applies participant scope.
- [HTTP routing](../services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt):
  settlement legacy handlers and `settlementReadResponse` use the generic read
  check; order reads use participant scope; command reads use client/participant
  scope and fail when scope is unavailable.
- [Settlement read gateway](../services/platform-runtime/src/main/kotlin/com/reef/platform/api/SettlementAdminGateway.kt):
  `settlementFactsResponse`, ledger and other read methods accept a run ID,
  without principal scope. `scenarioRunId` selects data; it is not authorization.
- [Existing boundary tests](../services/platform-runtime/src/test/kotlin/com/reef/platform/api/PlatformHttpServerBoundaryTest.kt)
  prove substantial authorization already exists.

Recommended next code slice: settle the visibility contract for the six
settlement read routes in `API_SURFACE_POLICY.md`, then add allowed/denied
run/participant/operator tests and matching enforcement. Preserve scenario
runner access through the intended authenticated contract. Do not silently
reclassify these routes or change accepted D-049 semantics in a doc-only audit.

## Recovered August 21 Projection Evidence

Raw artifacts are local under `reports/do-benchmark/`; they are not checked-in
promotion records. This section preserves the audited result if local files
are later removed. Checker ran on temporary copies to preserve original files.

| Run | Measured shape | Current checker result |
| --- | --- | --- |
| `do-benchmark-20260821T202554Z` | `2.5k/60s`, `149,975` attempted/accepted/direct-acked/materialized/projected, lag `0`, p95 `101.63ms`, p99 `248.33ms` | **Pass** under current named projection accounting, freshness, maintainer and diagnostics requirements. |
| `do-benchmark-20260821T204347Z` | `5k/60s`, `299,959` exact stage counts, canonical-projector lag `0`, p95 `104.54ms`, p99 `204.72ms` | **Fail**: final lifecycle maintainer `lastProcessed=500`; market-data maintainer `lastProcessed=32`, both required `0`. Equal canonical/projected counts alone do not prove downstream drain. |

Both telemetry sets show one `orderLifecycleProjectorEnabled=true` and one
`marketDataProjectorEnabled=true` among four projectors; the other three have
both disabled. Captured settings include `pg_stat_statements.track=all`,
`shared_preload_libraries=pg_stat_statements`, and I/O/WAL-I/O timing enabled.

Recheck used `scripts/dev/do-benchmark-check.mjs` with profile
`materializer-projection`, required rate `2500`/`5000`, minimum attempted,
accepted and projected rate `2400`/`4800`, zero lag/count-gap/deadlock/retry
limits, minimum 16 direct partitions, maximum skew 4, required DB/I/O/statement
diagnostics, and exactly one lifecycle and market-data maintainer. No extra
latency gate was imposed; reported latency is not a promotion claim against
the separate venue-core latency thresholds.

The August 20 `2.5k/5m` sustained baseline and failed `5k/5m` run remain the
recorded sustained boundary. August 21 short runs do not replace them.

## Evidence Still To Locate Or Produce

- Corrected Arena policy matrix across the planned multiple seeds and hosted
  game rehearsals, including immutable roster/policy/scoring/recovery evidence.
- Standalone live security-fail/repair and `ops-realistic-v1` pending reports.
- State-shape branch reconciliation plus local three-shape smoke and hosted
  baseline promotion.
- Fully drained `5k` projection short gate, then a sustained promotion run.

These are deliberately phrased as evidence gaps in the audited checkout, not
claims that the underlying features are absent or that no external run exists.

## Verification

- Targeted Kotlin boundary, runtime-profile, settlement, and engine-transport
  suites: `./gradlew --offline test` with class filters; 226 tests across 12
  classes passed, with zero failures, errors, or skips.
- Recovered remote artifacts: current checker passed `2.5k` and rejected `5k`
  for the two downstream-drain reasons above.
- Earlier checks in this audit session: Go app/protobuf suites, CI workflow and
  fan-in gate tests, onboarding links, and 285 local link targets passed.
- Doc-only edits; no runtime behavior, deployment, or throughput claim changed.

## Related Planning And Publication Sweep

The repository work board is now explicit in `WORK_PLAN.md`; no external board
was requested. The follow-up sweep aligned README, public status/Arena pages,
auth/provisioning, long-form Arena plan, system overview/diagrams, throughput
handoff, simulator backlog, API steering, and the documentation index.

Original intake rollout, separation-readiness, simulation-tuning and soak
checklists are marked historical/supporting so old unchecked items cannot be
treated as the current backlog. Accepted decision summaries and dated benchmark
reports retain their history, with current-status pointers where needed.

Additional source-verified UI backlog corrections: `+layout.svelte` already
has `my bots`/`game admin` nav and the login/trust/role strip; `bot-admin`
already has bot/secret-path copy actions, object-only JSON validation,
updated-at display, unsaved-change guards and clear/discard dialogs; `admin`
already has search/status filters, action buttons and `/admin/run` detail.
Broader filters, inline expansion, account-menu actions and dashboard/audit
polish remain candidates rather than falsely marked complete.

The public docs build passed (22 pages). It still emits the existing missing
404-content and sitemap-site-configuration notices; these are outside this
planning alignment. Historical files and unrelated feature catalogs were not
globally rewritten or marked complete.
