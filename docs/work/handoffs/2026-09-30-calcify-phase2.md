# Handoff: Calcify Phase 2 implementation

Nightly checkpoint, 2026-09-30 America/Toronto. User explicitly requested stopping point, session handoff and draft PR. Work paused before next correctness fix; do not treat implementation or sustained qualification as complete. This file records continuation context, not product truth.

## Objective And Boundary

Complete approved six slices in `docs/work/CALCIFY_PHASE2_IMPLEMENTATION.md`: D-042 run-scoped matching, full-fact Protobuf and pure resolver, verified-led Kafka Streams role, lane safety, operational recovery, and sustained resolver-stage capacity. Preserve deterministic replay, canonical source facts, durable ingress acknowledgement, same-lane ordering and explicit faults. Legacy post-match remains active. Ledger/allocation/clearing/settlement/cutover are outside scope.

User approved implementation, then explicitly paused for night. Resume when user asks; do not run new load or make further correctness changes as part of this checkpoint. Draft must remain unmerged until blockers below close.

## Canonical Sources

- `AGENTS.md`, `docs/AI_CONTEXT.md`, `docs/README.md` and relevant steering.
- `docs/work/CALCIFY_PHASE2_IMPLEMENTATION.md`: approved six-slice plan and current limits.
- `docs/research/CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md`: preserved bounded experiments before implementation.
- `docs/WORK_PLAN.md`: current dated work checkpoint.
- `docs/THROUGHPUT_BASELINES.md`, `docs/PERFORMANCE_LEARNINGS.md`, relevant historic L6/L8/L9 evidence: performance claims must retain workload/config/pipeline differences.
- `docs/DECISIONS.md` D-042, `docs/HOT_BOOK_SHARDING_PLAN.md`, `docs/LOCAL_CONFIGURATION.md`, relevant contract/boundary storage steering.
- `docs/evidence/calcify-phase2-implementation/README.md`, `review-findings.md` and per-run frozen policies/results.

## Current Repository State

- Implementation checkout: `/Users/dsteele/.codex/worktrees/calcify-phase2-experiments/reef`.
- Branch: `codex/calcify-phase2-implementation`; remote: `dills122/reef`; PR base: `master`.
- Code/tooling checkpoint: `d297c240c7eb0641cb5fb1a565c18e6b6bcd6027`.
- Retained experiments: `a200e022` and `fa8449de`, based on merged Phase1 PR #431 / `79dab22b`.
- Retained implementation commits: `1da3c5b3`, `781a4a5d`, `48f603a4`, `f86e892c`, `665366d0`, `a395c147`, `48566582`, `a3cc88b3`, `2089c739`, `3f0a16ca`, `ca507e05`, `d297c240`.
- Primary checkout `/Users/dsteele/repos/reef` remains on `codex/calcify-phase2-planning`; unrelated untracked `.planning/post-match-wave1/` preserved. Do not switch or overwrite it while resuming implementation.
- Nightly docs/evidence/handoff committed after code checkpoint. Final delivery metadata below records draft URL; verify `git status` and newer commits when resuming.
- Ignored Gradle/build outputs, `.env` and JFR binaries remain local. Profiler summaries/hashes are portable; binaries are not part of PR.
- No load/probe workers remain. Task-owned three-node RF3 test cluster, network and exactly three labeled test volumes removed. Reef stack already down with volumes preserved; unrelated Docker data untouched. Cleanup evidence under `nightly-checkpoint/`.

## Completed Work And Evidence

Go matching now scopes books by framed run/session/instrument, including submit/cancel/modify, lazy batch rollback, order state and replay. V3 snapshots retain scopes; old snapshots with nonempty run reject ambiguous shared books, empty-run compatibility validates legacy checksum before migration. Actual Go paired fixture produces200 accepted submits/100 trades with complete facts and long IDs; retained JSON source and deterministic replay assertion.

`contracts/proto/calcify.proto` adds complete accepted-order facts, immutable trade facts, exact source provenance, commitment identity and resolved V1 context. Pure resolver validates CRC, references, scope, side/currency/time/lane/ordering, immutable original acceptance economics and accepted-ID lifetime. Full-fact equality uses independently decoded String-source oracle against managed byte-source output.

Managed runtime uses Kafka Streams4.3.1 EOSv2, persistent Rocks state, sequential demand-driven read-committed source reader, accepted index/completed identities/target batch/pending frontier in changelog, bounded linked pending queue, decoded-row LRU and fault suffix. Ready target avoids queue churn. Operational role validates SQL source registration once, broker durability/byte caps, exposes loopback health/readiness/metrics, and retains infrastructure failure latch across close. No synchronous SQL hot-path write or scan.

Latest checkpoint local checks:

- Platform Java21 `./gradlew check`: **685 tests, zero failures/errors/skips**,61% instruction coverage gate passes. `platform-check-handoff.log`.
- Matching `go test -race ./...`: passes; latest invocation uses valid Go cache. `nightly-checkpoint/go-race.log`.
- Additive Protobuf descriptor and Go/Java generated-source drift checks: pass. `nightly-checkpoint/proto-governance.log`.
- Five JavaScript syntax checks and broker Compose validation: pass. `nightly-checkpoint/tooling-checks.json`.

Earlier integration evidence, not unchanged final-candidate signoff:

- `full-path/passed.log`: actual PostgreSQL HTTP ingress → Go matcher → Phase1 verification → production managed resolver, two durable intake rows and one exact2,588B Protobuf context. RF1/cached-base functional diagnostic; before later queue/checksum changes.
- `broker-a9bc0494`: RF3/fsync nine fault boundaries pass; spans linked queue build update. Covers source retention/UUID, not verified/output gaps from fresh review.
- `recovery-5545850c`:1m accepted rows,767,176,186B local state, cold same-app recovery observed RUNNING19,971ms including takeover/startup, exact500,100 after catch-up. Diagnostic120s objective; before latest producer/flow/cache/checksum changes.
- `capacity-156d616c`:100k hot exact11,121.32/s with JFR; one5s window12,019.45/s. Short diagnostic, not sustained qualification.
- `sustained-8ea6c8ce`: all3.15m exact/ordered/unique at10,160.50/s active read-committed covering rate. End gap1,184 and covering peak16,842 pass count gates; **producer310.730s fails frozen301s max**. Overall false; other profiles unrun. Preserve raw failed result.

Other sustained failures retained: `53a64664`10,605.80/s but127,510 end gap; `fa7fec51`9,881.14/s and210,491/274,978 end/covering gaps; `1fadb3e2` Docker VM ENOSPC, all brokers exit133. None proves sustained four-profile10k or upstream20k commands/s.

## Decisions And Rationale

- RF3 Redpanda requires `write.caching=false` on canonical/output/changelog topics. Kafka backend requires minISR2. Initial cached RF3 labels were corrected explicitly; no simultaneous power-loss claim.
- Production output batching128KiB/20ms/LZ4, normal100ms EOS interval;512KiB trial showed no observed gain and was reverted. Faults request prompt commit.
- Per task: source4MiB, source buffer16MiB, row64KiB, target/output16MiB, pending200 compact verifications, fault suffix≤200 and64KiB records; decoded accepted cache256/4MiB serialized. Rocks32MiB block cache plus16MiB×2 memtables/store, app cache8MiB. Heap object/string/native overhead is additional.
- Canonical checksum wire bytes unchanged: fixed1536 prefixes, one node-kind dispatch, per-digest128 shared string tokens (fields≤128 UTF-8 bytes, values≤32). Strict direct byte parser uses16KiB validation scratch, rejects malformed UTF-8, UTF16/32/BOM auto-detection; independent String oracle retained.
- Accepted and completed identities have no TTL. Native state governs existence/writes despite decoded cache. Explicit repair required for incompatible pre-release pending layout.
- Test fixture injection is resolver-stage workload, not simulator/manual HTTP capacity. One-worker two-thread capacity requests standby but cannot host its own standby; separate two-instance fault case exercises promotion.
- RF3 test Compose uses3×1CPU/1GiB Redpanda heap with2GiB container limits,32MiB segments, labeled named volumes, ports29192/29292/29392. Earlier segment/disk/backend differences preclude causal rate comparison.
- Preserve exact failed evidence and policy; no relaxed thresholds or reinterpretation of conservative covering counts as actual trade latency.

## Blockers And Limitations

**P1 verified-input retention reset:** current earliest reset can skip unstaged commitments after committed offset expires. Source-retention guard cannot detect lost verifications. Fix not started.

**P1 verified/output topic identity:** restored checkpoints/completed identities only bind source identity. Recreated verified topic can reuse overlapping offsets; recreated/missing output can lose published history while restored completed state suppresses replay. Fix not started. Detailed bounded review in `review-findings.md`.

Sustained qualification remains open; latest producer itself failed duration. Fault/recovery and production-role smoke require final fixed-candidate repeats. Infrastructure failure flag compiled/tested but actual supervised nonzero exit not operationally proved. Normal Docker build hit registry metadata deadlines; cached-base local diagnostic image is explicit, not normal build success. Existing custom runtime image predates latest code and must be refreshed before final smoke.

`sustained-check.mjs` currently writes `results.pass=false` but can exit0; inspect JSON when evaluating runs. Set nonzero exit for failed qualification before next final runs; preserve old run interpretation. No simultaneous namespace/changelog destruction proof or atomic malicious cross-topic replacement guarantee.

## Immediate Next Actions

1. Verify branch/checkpoint/status and read `review-findings.md`; implement **verified retention fail-closed guard first**. Set unprefixed `auto.offset.reset=none`, remove conflicting reset entries, pin `group.protocol=classic`. In consumer gate wrap both subscribe forms, preserve revoke/lost callbacks, bounded3s batched beginning/end/committed lookups on assignment. Existing checkpoint must be within inclusive[beginning,end]; missing checkpoint only allowed at beginning0 with explicit seek0 before original assigned callback. Active retention after assignment fails via resetnone; avoid per-poll network scans.
2. Add real-broker verified-retention negative case: consume130, stop, seed130 unconsumed, truncate verified through260, seed130, restart same app. Expect nonzero infrastructure failure and no new output; prior130 unchanged. Keep source truncation case.
3. Bind persisted generation plus names/UUIDs for all3 topics. Runtime `ensureTopics` returns verified/output IDs and rejects missing output before creation when app changelog exists. Extend existing once/sec metadata check to all3; production/probe cannot accept blank/zero IDs (blank only injected-reader fixtures). Restored source-only identity fails explicit repair; cannot infer previous input/output UUIDs. Cover missing/pre-recreated output, live output recreation, verified overlapping-offset recreation and normal same-UUID restart. Deleted-output case asserts no new output and failure, not preservation of already deleted history.
4. Fix fixture delivery, test-only: paired-seed currently sync waits on source future each wave. Try bounded FIFO16 in-flight source sends; emit each wave's100 verifications only after actual source acknowledgement and metadata offset, drain FIFO in lane order, flush final sends. No unbounded futures or verification ahead of durable source. Record producer/workload change. Short exact oracle trial first. Thresholds stay frozen.
5. Freeze rebuilt candidate; rerun RF3 fault matrix (existing9 plus added input/output cases),1m-row cold recovery, then300s hot/spread/skew/aged at10.5k offered. Require≥10k durable/s, end gap≤20k, covering gap≤100k, source≤301s, drain≤90s, exact full Protobuf parity. Aged bootstrap1m accepted rows. Monitor disk; fail stops later profiles. No Gradle/profiling work during measured loads unless frozen protocol includes it.
6. Refresh runtime installDist/cached diagnostic image or complete normal build if registry works; fresh actual HTTP smoke plus real infrastructure nonzero exit proof. Keep role-level startup/SQL checks separate from probe bypass. Finish focused/full regressions and second-model review of fixes, update canonical evidence and draft. Merge only after approved gates close.

## Verification Commands

Run from implementation checkout. Default Java may be25; explicitly select21. Bun1.3.14, Go1.26.5, protoc33.2 used at checkpoint.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home services/platform-runtime/gradlew -p services/platform-runtime check resolverProbeDependencies installDist --console=plain
(cd services/matching-engine && go test -race ./...)
PATH=/Users/dsteele/go/bin:$PATH ./scripts/check-proto-additive.sh
docker compose -p reef-calcify-resolver-test -f scripts/dev/calcify-resolver/broker.compose.yml up -d
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home bun scripts/dev/calcify-resolver/broker-check.mjs
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home bun scripts/dev/calcify-resolver/recovery-check.mjs
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home CALCIFY_SUSTAINED_WAVES_PER_SECOND=105 bun scripts/dev/calcify-resolver/sustained-check.mjs
```

Apply pending fixes before broker qualification. Harness writes fresh UUID-named evidence. Check result JSON, not exit status alone. Use exact owned-project `down --volumes` only for disposable test cluster; regular Reef `dev-down` preserves volumes. Never broad prune: unrelated user volumes previously accounted for106.5GB.

Full-path fresh diagnostic (images must be refreshed first):

```sh
DEV_COMPOSE_BUILD=0 REEF_PLATFORM_RUNTIME_IMAGE=reef-platform-runtime:calcify-p2-local REEF_MATCHING_ENGINE_IMAGE=reef-matching-engine:calcify-p2-local DEV_CALCIFY_FULL_PATH_ID=calcify-p2-resume-fresh DEV_CALCIFY_PHASE2=1 DEV_CALCIFY_FULL_PATH_TIMEOUT_MS=90000 bun scripts/dev/calcify-full-path-smoke.mjs
```

Use new full-path ID on each attempt; prior output topic byte caps can intentionally fail new startup checks. Previous custom images were built with host Java21 installDist and GoLinuxarm64CGO0 using cached runtime base, because normal Docker registry metadata requests timed out. Image creation/manifests and failed staging logs retained in `full-path/`.

## Delivery Metadata

- Repository: `dills122/reef`; branch `codex/calcify-phase2-implementation`; base `master`.
- Draft PR: publication pending; replace before final checkpoint.
- Code checkpoint `d297c240`; subsequent docs/evidence/delivery commits retained in branch history.
- Dirty tracked/untracked work at final handoff: none in implementation checkout. Ignored local build outputs/JFR remain; primary checkout's unrelated `.planning/post-match-wave1/` preserved.
- No new chat, scheduler or deployment created; branch/PR only. Resume in managed checkout above.
- GitHub operations: use explicitly invoked `$github-keychain-auth` at `/Users/dsteele/.ai-central/templates/skills/first-party/github-keychain-auth/SKILL.md`. Request outside-sandbox execution; run `env -u GH_TOKEN -u GITHUB_TOKEN gh ...` and let Git credential helper resolve Keychain for push/fetch. Sandbox auth check is unreliable; outside-sandbox Keychain-backed access verified for `dills122`. Never extract or pass credential bytes.
