# O2 mixed-prefix capture fault offset research — October 10, 2026

Read-only bounded research. Researcher, not independent reviewer; combined review count unchanged at 0/3. No application/source edits, build, tests, live-resource commands, or resource state changes. Only this new private planning note written. Shared checkout/other edits preserved.

## Actionable result

`captureSourceOffset` is literal physical Kafka SOURCE offset, not record index, command offset, publication ordinal, or represented frontier. Current mixed-prefix helper hardcodes `0`; main12 capture receipt identifies first actual SOURCE record at `1`. With same first-source position, probe never emits `capture-0.event.json`; cmd01 capture can commit and helper fails at 15-second gate wait before intended real JVM kill. This is fixture/probe selection mismatch, not evidence of capture production defect.

Prefer pre-arming capture observer by exact cmd01 identity before JVM startup; derive actual SOURCE offset from matching completed receipt inside synchronous precommit callback, then emit/validate actual offset. Deriving selector from history after posting cmd01 is too late: capture may already have committed. Do not guess fixed `1`; observed value belongs to main12, not universal invariant.

## Current source evidence

Source base HEAD `5730b6be1ef1fe702be5f3d5bf752e5233ab9016`; application/fixture O2 files include uncommitted work. Line citations describe source observed during this spike.

- `services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/FiniteO2FaultProbe.kt:29`: optional fourth argument parsed with `toLong`, checked against finite maximum offset.
- Same file `:48–57`: capture observer selects `state.completedRecordsList.lastOrNull()`, compares `record.source.sourceOffset == captureOffset`, writes physical offset, topic/topic ID, generation, partition, content/state digests and command IDs; blocks at file gate named `capture-$captureOffset`.
- Same file `:34–40`: actual synchronous gate writes private event, awaits release for up to30 seconds. No ordinal or genesis conversion.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/FiniteLifecycleCaptureProcessor.kt:305–315`: gets source topic/partition/offset from Kafka processor metadata; resume=`offset+1`; reduces, forwards envelope, stores state, synchronously calls observer before process returns.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/FiniteCaptureLive.kt:33–34`: observer wired directly from processor to test wrapper; observed state set first. `FiniteLifecycleCaptureRuntime.kt:37,66` configures exactly-once-v2 and100ms commit interval. Blocking synchronous process callback holds intended boundary before Streams task returns to commit path.
- `scripts/dev/calcify-financial/finite-o2-proof.mjs:456,458`: dedicated mixed fault only permits PUBLISHING cmd02 plus literal `captureSourceOffset === '0'`; passes literal as fourth probe argument.
- Same file `:549–552`: mixedPrefix checks literal0, posts cmd01 and requires durable202, posts cmd02 without awaiting final response, waits for cmd02 PUBLISHING event, then exclusively reads `capture-0.event.json` and checks `sourceOffset === '0'`. Only after capture gate does it wait for PUBLISHED/UNKNOWN/STOPPED journal and require SOURCE group0/CAPTURE committed-end0. `:553` performs runtime kill/matcher stop and normal runtime restart.
- `FiniteIngress.kt:42–49`: possible publication charged before PUBLISHING observer; timeout scheduled before blocking observer; actual publisher send occurs after observer returns. `:83–87` timeout records UNKNOWN and fences admission. Thus blocking cmd02 prevents actual command publication while retaining honest uncertainty accounting. Capture and ingress gates run on separate processing paths.

Current source SHA256:

| Path | SHA256 |
| --- | --- |
| `scripts/dev/calcify-financial/finite-o2-proof.mjs` | `8300471129bee5afb9471aacf678191eeba66f39bbf47ecbe3ab191ebbcd4bdb` |
| `services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/FiniteO2FaultProbe.kt` | `6262d554fc314a9d62377310c68d096d91c2721dd74e8e488d8063cce4f9e1fa` |
| `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/FiniteLifecycleCaptureProcessor.kt` | `9a4696a18ec3bac3579bf4065f21e2d2116fae6c0100576e7e4daceba501a009` |
| `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/FiniteCaptureLive.kt` | `82a50dc8fb38faeb3a3b80f0cb43ea54c74a024e0b30fb4632a2e98299687f3a` |

## Actual retained main12 data

Artifact `/private/tmp/calcify-o2-20261010-main12/proof/0208-typed-history-one-command-research.json`, independently checked SHA256 `1d8d2868f44d36beae29e59b6312927601919a1ec851f209f14e616fd0e00ce3`.

Scope expressly one canonical publication diagnostic; no full flow, closure, replay or O2 acceptance claim.

- COMMAND retained record offset0; journal PUBLISHED cmd01, streamSequence1/publicationOrdinal1.
- CAPTURE retained record Kafka offset1. Decoded `valueBase64` protobuf with small read-only wire parser: envelope field3 is `SourceProvenanceV1`; its field3 is source_offset1. Source generation1, partition0 (protobuf omitted default), SOURCE topic `calcify-o2-20261010-main12-source`, topic ID `IK4x--rsQlaWuwtS9V_y0A`; source batch ID `calcify-o2-20261010-main12-shard-p0-1-1`. Envelope field11 resume_offset2.
- `contracts/proto/calcify.proto:29–38,162–174` confirms field mapping.
- Capture readiness completedRecords1, representedNextOffset2, sourceGroupNextOffset3, captureReadCommittedEndOffset3, retainedSuffix0/fault empty. SOURCE and CAPTURE cuts both beginning0/end3/readCommittedEnd3.
- SOURCE history result is **error**, code `history_unsupported_codec`, codec3, recordCount0. Therefore raw SOURCE history offset1 is not established by this artifact. Capture receipt directly establishes recorded source provenance; live readiness additionally verifies actual retained SOURCE record offset/hash against receipt (`FiniteCaptureLive.kt:48–52`). Fresh main13 raw SOURCE proof must close probe decoding gap separately.

Do not infer source offset from COMMAND offset0, captured-record count1, SOURCE LSO3, represented2 or publicationOrdinal1 alone. They are distinct values.

## Transaction-gap assumptions

Go `services/matching-engine/internal/streamdirect/kafka.go:512–546` publishes SOURCE in transaction, adds COMMAND group offset, then commits. Code uses broker-assigned offsets; no guarantee first user data occupies0 or1. Main12 gaps consistent with transaction/control records; exact record at offset0 not classified by retained unsupported SOURCE history, so cause remains unproven here.

Production proof already allows physical gaps: `FiniteCaptureLive.kt:79–86` compares actual committed SOURCE offset list with completed receipt list, allows group frontier above represented frontier. `FiniteCaptureProofTest.kt:11–16` specifically accepts control gaps. Keep those semantics; no contiguous-offset/count assumptions should enter fixture fix.

## Minimal correction choices and required checks

Preferred test-only identity selection:

1. Freeze exact cmd01 capture target alongside existing cmd02 PUBLISHING target before runtime starts; validate both stay inside dedicated mixed-prefix lease.
2. Observer selects completed SOURCE receipt containing cmd01 command member. Require exact binding/lane and expected first completed prefix; derive physical offset from receipt and block immediately within existing observer call. Gate filename/selector must not require caller to know offset before observing event.
3. Event retains actual offset, source generation/partition/topic/topic ID, command identity, source content digest, state digest, PID/class-manifest hash. Supervisor validates same composition, cmd01 and lane; where available cross-check exact retained committed SOURCE payload/offset against event digest before crash. No capture release before kill.
4. Preserve cmd02 UNKNOWN timeout path, journal/counter equality, command-history absence, actual runtime containment, matcher stop, capture-first normal restart, drained known prefix, activation/refund refusal.

Minimal file/manifest change map for author implementation, after main13 no-fault live candidate is closed:

- `services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/FiniteO2FaultProbe.kt`: replace optional fourth argument meaning with exact capture command ID selector (e.g. lease prefix + `-cmd-01`), validated as command identifier. Keep second/third arguments dedicated ingress `PUBLISHING`/cmd02. In capture observer select matching command member, derive `record.source.sourceOffset`; use command-based gate filename and event `captureCommandId` plus existing actual source fields. Require first completed receipt and exact configured digest/lane; emit once before returning from callback. Keep existing private-file/no-symlink/create-new and timeout behavior.
- `scripts/dev/calcify-financial/finite-o2-proof.mjs`: `faultLaunch` and `mixedPrefix` accept exact `captureCommandId === prefix + '-cmd-01'`; fourth argument now command ID. Gate path based on target command, while offset read from event as bounded decimal. Validate generation/partition alongside existing binding/topic UUID/PID/class-manifest checks. Read retained committed SOURCE at stable cut; require exactly cmd01 matching actual physical offset and payload digest. Recheck broker topic identities and cuts remain stable around read/event verification. Keep SOURCE group0 and CAPTURE committed-end0 assertion until kill.
- Exact dedicated mixed-prefix lease JSON/config where manager allocates fault (path not yet supplied): replace `captureSourceOffset:'0'` with frozen `captureCommandId:<prefix>-cmd-01`. New optional field is test fixture launch configuration, not production API parameter. Reject obsolete/ambiguous selectors.
- `scripts/dev/calcify-financial/finite-o2-proof.test.mjs`, focused test-probe tests/helper: add selector/event identity checks below and retain physical-gap coverage.
- `.planning/calcify-p3-o2-2026-10-10/alignment/fixture/author-packet.md:16`: correct G8 from literal offset0 to command-prearmed actual SOURCE receipt gate. Update any active fault instructions/fixtures found by focused `rg captureSourceOffset`; archives remain historical.
- Rebuild test probe only after main13 no-fault resources close. Freeze rebuilt test classes/dependencies with existing faultProbeDistribution manifest path, SHA256/files/count/class-manifest reconciliation; update fixture/author manifest and source artifact manifest together. If unchanged production artifact reused, distinguish test probe changed bytes explicitly. No manifest edit alone can substitute for rebuilt loaded classes.

Focused test requirements before executing corrected fixture:

- Selector accepts exact cmd01 completed receipt at physical offsets0,1 and gapped higher offset; same offset with wrong command/binding/lane does not qualify.
- Capture observer remains held before committed SOURCE group/CAPTURE output advances; cmd02 timeout still creates UNKNOWN while actual command history stays one record.
- Wrong PID/class manifest/topic ID/generation/partition/offset/digest/event target is refused; stale/duplicate private gate evidence cannot qualify.
- Supervisor no longer derives SOURCE offset from command ordinal/count/LSO; post-crash proof uses actual source receipt and committed membership even when group frontier exceeds resume.
- Existing pure `FiniteCaptureProofTest` gap test remains necessary but cannot prove real precommit crash behavior. Fixture unit checks plus real dedicated mixed-prefix evidence required. No tests executed in this spike.

Contract/boundary impact: test-only selector/fixture correction need no public API, source-event or capture-protobuf schema change; physical `SourceProvenanceV1.source_offset` meaning stays intact. Update focused fixture/probe tests, lease semantics, author packet and latest proof docs; freeze/hash rebuilt test probe if wrapper changes. Apply repository delivery/retention pass to eventual implementation; this read-only private research note creates no superseded owner document.

## Graph/source method and limits

Read AGENTS, AI_CONTEXT, docs map, current Calcify O2 work-plan section and relevant finite source contract. Applied codebase-memory Verify workflow: list_projects/index_status then search_graph for FiniteO2FaultProbe; no result. Indexed `reef` root is different worktree `/Users/dsteele/.codex/worktrees/8576/reef`, generation2026-09-28T02:46:53Z. Exact evidence-path coverage returned missing freshness for all ten cited source/test/contract paths. Used current checkout targeted source reads/rg fallback; no graph absence or exhaustive claims. No graph reindex or repository-wide audit.

Unresolved: raw SOURCE offset proof for main12, exact offset0 gap classification, fresh main13 behavior, corrected mixed-prefix runtime proof. Main11 completed12/represented35/group36 supplied by manager; not independently inspected in this bounded spike.
