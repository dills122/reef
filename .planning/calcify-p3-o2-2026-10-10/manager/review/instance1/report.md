# Independent engineering review — O2 combined code and plan

Review instance: 1 of 3. Verdict: **Not ready**. Frozen dirty/untracked target at HEAD5730b6be1ef1fe702be5f3d5bf752e5233ab9016; branch `codex/calcify-p3-o2-2026-10-10`.

## Findings

### P1 — G9 actual managed-state transport qualification remains open

Evidence: `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/.planning/calcify-p3-o2-2026-10-10/manager/acceptance-matrix.md:17`; `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/.planning/calcify-p3-o2-2026-10-10/manager/implementation-contract.md:63`; `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/FiniteLifecycleCaptureRuntime.kt:55–67`; `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/FiniteCaptureProofTest.kt:31–103`; `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/services/matching-engine/cmd/calcify-finite-probe/main.go:1159–1209`.

Accepted G9 explicitly requires full6307840-byte managed-state producer/changelog/fetch/request/topic fit. Actual MAIN17 `0143-probe-wire-fit.json` proves separate Sarama transactional binary canary at6307840B with NONE and request/fetch6308864; source17 product objects match22. Java run03 proves pinned NONE/magic2 record sizing offline, with five-byte `state` key and no headers: estimated6307933B and actual6307919B. It never instantiates producer, opens broker, writes managed changelog or restores typed state. Kotlin original typed serialization receipt reports113590B high-water order history,1049532B full malformed suffix,65419B trade fixture; `exactReachableWorstStateMeasured=false`. Actual MAIN18 managed restart restores small twelve-record state with exact matching digest. Each result valid in own scope; combining them still leaves actual Java managed path at accepted conservative envelope unverified.

Failing acceptance scenario: delivery labels G9 complete because binary canary, offline codec and small typed restore pass. They do not establish largest allowed managed serialization will traverse configured Java producer/request/changelog/fetch/restore path, or decoded high-water workload will fit observed finite resource profile. Impact: required delivery gate missing; admitted closure/restore qualification cannot be inferred for declared upper envelope. No actual max-state failure asserted.

Smallest credible correction: retain G9 OPEN; add bounded proof packet identifying maximum reachable typed-state size or defensible upper bound from component/field limits, actual configured Java managed-state transport and same-binding restore obligations, and finite decoded/native observations within existing product caps. Conservative6307840 envelope may remain conservative; no need fabricate unreachable workload or demand exact natural maximum measurement. Full binary envelope alone remains transport proof. New live work requires fresh root/user resource authorization; existing grant spent. No architecture pivot or test-infrastructure optimization justified by this gap.

### P2 — Finite matcher does not enforce pinned no-compression profile

Evidence: `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/services/matching-engine/internal/streamdirect/calcify_finite_credentials.go:102–120` calls `newKafkaClientConfig`; `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/services/matching-engine/internal/streamdirect/kafka.go:699,732–737` selects ambient compression with default `lz4`; `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/services/matching-engine/internal/streamdirect/calcify_finite_startup.go:82–99` effective-config gate ignores compression. Canonical source contract `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/docs/work/CALCIFY_FINITE_P3_SOURCE_CONTRACT.md:156–165` requires pinned no compression and finite fetch/decode preflight. Fixture `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/scripts/dev/calcify-financial/finite-o2-proof.mjs:451` explicitly sets NONE, making successful live evidence valid for fixture branch.

Failing scenario: enable valid bound finite runner with correct witness/routing/ownership, omit `MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE`, or inherit global `STREAM_ACK_KAFKA_COMPRESSION_TYPE`. Startup accepts; SOURCE producer uses LZ4/default or alternate codec. Finite mode has no codec rejection or override despite no-compression qualification. Impact: activated finite configuration falls outside accepted preflight/profile; existing NONE resource/wire evidence no longer applies. Original MAIN12 typed-history receipt0208 and matcher-configuration0082 hash-match research anchors: codec variables absent, SOURCE history rejects actual codec3. This demonstrates why fixture pinned NONE. This finding does not invalidate actual correctly pinned campaign receipts.

Smallest correction: set finite-only Sarama `CompressionNone` in authenticated finite client config, or reject non-NONE effective producer codec before activation. Add focused test for absent/ambient changed codec with finite mode plus ordinary-mode codec compatibility. No live campaign needed merely to prove config selection; affected acceptance retesting remains root decision.

No further confirmed code defect in inspected scope. Preliminary ACK race concern cleared: timeout503/late durable PUBLISHED repair conservative; no202 precedes real ACK+marker. Preliminary replay concern supported by real prefix/suffix and capture membership receipts below.

## Plan Review

G1–G8 have substantial implementation and original executed evidence for scoped finite fixture. Immutable opening/counters persist; atomic SQL run-row lock reserves last token, authorization can drain reserved last publication after STOPPED; UNKNOWN retains charge, no resend/refund, late original ACK monotonically repairs. Dedicated HTTP reuses canonical envelope/auth; engine HTTP/gRPC/direct stream bypass fenced. Startup checks actual UUIDs/configuration/ranges and complete registered COMMAND membership before replay/listener. Prefix replay emits no SOURCE; known PUBLISHED suffix drains transactionally. Managed capture verifies committed SOURCE/CAPTURE/changelog byte correspondence and source membership across control gaps. Registrar-only stage/release checks current epochs/accounting/journal digest; runtime lacks release/table authority.

Original MAIN20 proves16publications then eight future refusals with unchanged counts and closure; MAIN22 proves32attempts/onepublication then unchanged flood counts. MAIN23/24 retain RESERVED/PUBLISHING uncertainty; MAIN25 BROKER_ACK repair leaves STOPPED and stable charge; MAIN26 PUBLISHED restart; MAIN27 late ACK leaves original503 and no resend; MAIN28 restores owner2→3 while known prefix captured and unresolved send remains STOPPED. MAIN30/31/33 actual UUID/scope/lost-genesis refusals include nonzero exited, nonOOM process/listener witnesses. MAIN35 actual12COMMAND/12SOURCE/12CAPTURE,8accepted/4rejected,15members,3trades6units, source offset control gaps and clean terminal containment. MAIN18 twelve-record restart and local cache/changelog recovery separate source18 scope; counters/journal and capture digest identical after owner replacement.

No G9 waiver inferred from canonical distinction between serialized and physical bounds. Full process capacity remains unclaimed. G10 still requires finding reconciliation and final delivery/retention/publication; current retention checker PASS validates existing inventory, not completion of unpublished O2 archive work. Owner docs describe O2 candidate/in-progress and dependent O3–O5 gates; sensible continued gating. Original pacing exceeded, failures retained, source/config/resource changes disclosed. No heavy pivot inferred.

## Author-Claim Reconciliation

| Author claim | Evidence inspected | Status | Review consequence |
| --- | --- | --- | --- |
| Frozen55source22 paths and docs | Current hashes, HEAD/branch, exact dirty/staged boundary | Confirmed | Correct working-tree target, no O2 commit implied |
| Durable202 only after real ACK+PUBLISHED | FiniteIngress/SQL functions; focused Kotlin tests; original crash/lateACK receipts | Confirmed in scoped path | Race conservative; no premature202 finding |
| Stop/drain/replay/reset avoidance | Go recovery + capture reducers; MAIN20/22–28/35; MAIN18 exact journal/state digest | Confirmed in executed fixture scopes | G2/G3/G5/G6/G8 substantive evidence |
| Authentication/authority/bypass refusals | Finite HTTP/engine routes + SQL grants; actual authority/API isolation receipts | Confirmed in stated isolated scope | No extra production/adoption credit |
| Full-size canary + offline Java sizing | MAIN17 actual canary; run03 receipt/log; typed model receipt; MAIN18 restore | Confirmed with stated limits | G9 still OPEN; separate facts do not imply actual full Java managed path |
| Current112Kotlin tests + corrected9fault tests | Original summary/freeze and fault receipt/artifact manifest | Confirmed in distinct source scopes |23-file production freeze matches except subsequently corrected test-only fault selector; no fresh Gradle execution |
|151public harness checks | Retained hashed log + fresh host-access rerun | Confirmed | No monitor-infrastructure changes needed |
|10private operator checks | Hashed source22 receipt/log | Retained evidence confirmed | Static SHA pin scope only; no independent rerun |
|12terminal passes; MAIN34 remains failed |171ledger refs hash-match, original results/phase/gate facts and terminal receipts | Confirmed | MAIN34 retrospective supplement kept offline; MAIN35 independent fresh actual result |
| Mode-off compatibility | Current Go module tests/race/vet, snapshot omitted-field checks, retained Kotlin legacy suite | Supported | P2 finite codec correction must preserve ordinary mode |

## Verification Performed

- `go test ./...` from `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/services/matching-engine`: PASS, including final standalone probe.
- `go test -race ./internal/app ./internal/streamdirect`: PASS.
- `go vet ./...`: PASS.
- `bun --smol test scripts/dev/calcify-financial/finite-o2-proof.test.mjs scripts/dev/calcify-financial/finite-o2-resource-monitor.test.mjs scripts/dev/calcify-financial/finite-o2-engine-adverse.test.mjs`: initial sandbox150pass/1fail, `/bin/ps` EPERM; exact host-access rerun151pass/0fail,27.07s. Log `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/.planning/calcify-p3-o2-2026-10-10/manager/review/instance1/bun-tests.log`. No source/harness edit.
- `git diff --check`: PASS.
- `bun scripts/ci/check-records-retention.mjs`: PASS;520archived files/current links/fixtures/checksum companions. Not O2 publication completion.
-55public source hashes +10owner docs +4canonical requirements match; tracked dirty set unchanged; staged set empty. Original6914untracked boundary retained; additional files only root review bookkeeping and instance1 reports, all outside frozen source/docs. Boundary evidence `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/.planning/calcify-p3-o2-2026-10-10/manager/review/instance1/boundary-verification.json`.
-27author evidence anchors and171campaign ledger references current-byte hash-match, including original failed MAIN34. Gate receipt facts inspected rather than trusting PASS flags. `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/.planning/calcify-p3-o2-2026-10-10/manager/review/instance1/retained-evidence-hash-check.json`, `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/.planning/calcify-p3-o2-2026-10-10/manager/review/instance1/campaign-evidence-inspection.json`.
- Current source validators reapplied offline to original MAIN35 raw command/source/capture records; source checksum/provenance/dispositions/membership/trades match original canonical proof exactly. `/Users/dsteele/.codex/worktrees/fa2b-0041/reef/.planning/calcify-p3-o2-2026-10-10/manager/review/instance1/main35-offline-validation.json`. Node inline script PASS; first Bun stdin invocation printed CLI usage and executed no check, not counted.
- Graph `reef` September28 generation points to different checkout; O2 source paths missing. Direct source fallback used. No exhaustive graph claim, reindex, live services, Gradle/build, resource changes, Git writes or implementation edits.

## Open Questions And Residual Risks

G9 concrete qualification remains missing. Actual observations are sampled, not peak/native hard bounds or capacity guarantees. Source18–22 whole manifests differ; matcher/probe/distribution/faultProbe objects identical. Original MAIN17 canary uses same product objects17→22. Current112Kotlin production summary predates corrected fault selector; separate9checks and pinned fault distribution preserve that scope. No final artifact adoption or external effects reviewed. No security scan verdict implied by engineering review.

## Verdict

**Not ready** for complete combined O2 acceptance. P1 G9 accepted proof obligation open; P2 finite codec profile enforcement needs bounded correction. Scoped implemented G1–G8 remain usable reviewed evidence, but no O3–O5 release. Review instance1of3 consumed; root owns finding responses and whether material corrections justify remaining instances.

## Recommended Next Actions

1. Root responds per finding: Accept, Dispute with evidence, or Defer with owner/rationale. Preserve gates and original receipts.
2. Prepare smallest G9 proof-obligation packet within accepted finite scope; obtain fresh explicit resource grant only if actual live qualification needed. Do not start optimization, inflated unreachable workload or new workstream.
3. Pin/reject finite codec and focused regression check; refresh affected source/evidence hashes before any next review.
4. Complete G10 owner docs, retained latest evidence/archive pass and final Git/PR delivery after findings reconciled. No source edits performed by reviewer.
