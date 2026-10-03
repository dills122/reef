# Calcify financial E0 independent review

Review instance: 2 of 3

Frozen PR diff: `64f519361bf8a9f36d10d65d720a6b0ad1817e4c..eccdef965594bf534c3010c0648753cdff6c4b49`.
Execution worktree: `/private/tmp/reef-calcify-financial-e0`.
Branch: `codex/calcify-financial-sprint1-e0`.

## Findings

No new actionable findings. Prior P2 output-corruption defect fixed: `scripts/dev/calcify-financial/lib/output-capture.mjs:2–10` copies Buffer chunks, echoes bytes and concatenates bytes; `record-attempt.mjs:22–28` separates spawn diagnostics from child output and persists Buffers. Direct helper test forces split multibyte and invalid bytes on both streams. CLI test checks persisted binary bytes, nonzero child status, sequential duplicate refusal, missing executable diagnostics and revision hashes. Both tests pass independently; Make/CI wiring present.

Historical capture limitation remains disclosed. Correction adds new logs and appends attempts; existing logs unchanged in `59917fd4..eccdef96` raw diff. No claim that earlier recorder was byte-safe or that checksums establish original capture correctness.

## Plan Review

Scope verified from actual clean Git state and complete frozen path/diff set (104 files). No changed paths under production `services/`, `contracts/`, `packages/` or `apps/`. Primary dirty checkout untouched. Pinned canonical RFC packet matches `git show cda4185b8065bd38126c7547b30fef79a813f68a:docs/work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md` byte-for-byte (SHA256 `83a095162705fc461f6c9af1df28002dededeb84f0a530f28b14e486ca279843`). Current RFC updates only source identity correction and E0 status. Applicable AGENTS, AI context, documentation map, delivery/retention policy and repository steering inspected.

| E0 requirement | Evidence and assessment |
| --- | --- |
| Six language-server source queries | Six raw responses name relevant Svelte/Go/Kotlin/bash/Terraform/YAML paths and report shared language-manager initialization error. Successful gate missing, accurately blocked in README, board and RFC. Activation metadata never counted as success. |
| Exact-root graph and coverage | Exact root indexed, generation `2026-10-03T12:56:58Z`, 14253 nodes/72503 edges. Matcher identity symbol/snippet retrieved. Cited matcher/resolver paths show metadata matches/no recorded issue. Scripts excluded; direct reads used. Initial resolver name searches returned no nodes; no absence claim inferred. |
| Compiler and existing tests | Retained clean Java21 build successful after documented failures. Current materialized XML:16 Calcify suites/70 tests/0 failures/errors/skips. Current 48 materialized jar byte counts/hashes match manifest. No Gradle rerun or financial/EOS inference. |
| Frozen input contracts | Generator and committed JSON agree:20 cases/52 inputs; action/digest/context/execution identities, exact units, required payload, replay frontiers, schedules/seeds/mutations pinned. Golden arithmetic reviewed, including both insufficiencies, funding repair, due order, overflow and one-nano boundary. All genesis journal groups balance by asset; opening legs and expected balances fit signed64. |
| History/genesis/reconstruction | Explicit opening resource accounts, stage/business distinction, crash/checkpoint cuts and strict suffix specified. Example intentionally abbreviated; README requires E1 to complete encoded transitions. No E0 evolve/oracle/reconstruction success claim. |
| Source edges | `service.go:1313–1322` length-frames identity components. Focused framing/restore/rollback/IOC/Calcify/reuse test command independently passes both packages. Resolver immutable acceptance conflict at `MatchContextResolver.kt:92–96`, processor run/order key at `CalcifyResolverProcessor.kt:137–138` supports disclosed same-run reuse gap. RFC correction grounded in existing code, not new matcher behavior. |
| Environment/isolation | Runtime/jar facts separated from intended RF3/project/ports/volume registry. Docker guest disk, image digests, live durability/topic settings explicitly false/null/unverified. Offline config retained; no broker resources created or inspected by reviewer. |
| Research/measurement | Bounded primary documentation supports Kafka commit uncertainty/application resend limits, Redpanda topic deletion/remote-recovery caveats and PostgreSQL snapshot distinction. No runtime qualification inferred. E4 workload/cost rules prospective; no E0 throughput claim. |
| Delivery/retention | Owner board/RFC updated; full new bundle selected in inventory,94 checksums pass, retention checker/9 tests pass. New topic bundle with no superseded sprint bundle; explicit archive no-op justified. |

Next slice finite and proportionate: test-only FinancialKernel/FinancialOracle/FinancialKernelTest, journaled genesis and two-asset capture/settle,20 golden cases, independent BigInteger oracle, every-business-prefix comparisons, full owner replay/checkpoint suffix, seeded schedule/crash/mutation controls before E3. E1b holds remain proposed pending owner acceptance; source integration and broker proof separate gates. No heavy pivot needed.

## Author-Claim Reconciliation

`PRELIMINARY.md` saved before reading separate AUTHOR_EXPLANATION.md, evidence README rationale or retained instance1 report. Bootstrap/status/environment necessarily revealed correction intent; blind pass limited accordingly. Prior report consulted only after preliminary pass; verdict independently reconstructed.

| Author claim | Evidence inspected | Status / consequence |
| --- | --- | --- |
| Corrected recorder retains exact bytes | Helper/CLI/tests and independent 2-test rerun | Confirmed. Prior P2 closed. |
| Historical bytes preserved, new hashes scoped | Raw correction diff, environment.recorderRevision, four current revision hashes | Confirmed. Original capture limitations remain historical. |
| Synthetic inputs ready while E0 tooling blocked | Generator/result, raw six errors, explicit manifest flags, README/RFC | Confirmed preparation scope only. |
| 70 tests and runtime jars pinned | Clean build raw/attempt metadata,16 XML suites,48 jar checks | Confirmed retained execution; not newly rerun compiler. |
| Matcher framing warning stale | Graph snippet, unchanged production source diff, independently rerun source regressions | Confirmed RFC correction justified. |
| Reconstruction shape needs completion in E1 | Generator history sample, expectedFullState disclaimer, README next slice | Confirmed; no complete-authority claim earned. |
| Recorder serial/bounded/post-exit only | Existence check and ordinary writes; explicit corrected author limits/README | Confirmed bounded local usage. Concurrent same-ID or crash-safe execution unsupported. |
| Planning merge documentation-only | Complete frozen changed-path diff; historical open-PR snapshot and later correction | Confirmed scope. Earlier wording retained as history; no current unmerged dependency inferred. |

Primary research checked against [KafkaProducer4.3.1 API](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html), [Redpanda transaction documentation](https://docs.redpanda.com/streaming/current/develop/transactions/) and [PostgreSQL16 isolation](https://www.postgresql.org/docs/16/transaction-iso.html). Redpanda current page supports cited boundaries; attempted version-specific `/26.2/develop/transactions/` URL unavailable. This does not verify actual26.2.3 broker behavior. Handler/testing/disk sources not separately re-browsed in this pass; no new runtime assertion depends on them.

## Verification Performed

All repository commands explicit workdir `/private/tmp/reef-calcify-financial-e0`, except Go command in its `services/matching-engine` subdirectory.

- `git status --short`, `git rev-parse HEAD`, `git branch --show-current`: exact frozen head/branch, clean before and after.
- `git diff 64f519361bf8a9f36d10d65d720a6b0ad1817e4c..eccdef965594bf534c3010c0648753cdff6c4b49 --check`: pass.
- `node --check scripts/dev/calcify-financial/freeze-fixtures.mjs`: pass.
- `node --check scripts/dev/calcify-financial/record-attempt.mjs`: pass.
- `node --test scripts/dev/calcify-financial/record-attempt.test.mjs`:2/2 pass; isolated temporary test repository removed by test cleanup.
- `bun scripts/dev/calcify-financial/freeze-fixtures.mjs --check`: pass;20/52; SHA256 `fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.
- `bun run repo:check:records`: pass;423 archived files.
- `bun run repo:test:records`:9/9 pass.
- `node scripts/dev/script-surface-check.mjs`: pass;261 scripts,249 Node syntax,11 shell syntax; existing Deno skip disclosed.
- `shasum -a 256 -c docs/evidence/calcify-financial-sprint1/SHA256SUMS`:94/94 pass.
- `go test ./internal/app ./internal/streamdirect -run 'Test(TradeIdentity|MatchFactsReplaySnapshotAndRollbackExactly|IOCResidualNeverRests|Calcify|ReusedOrderID|SnapshotRestoresReused)' -count=1`: both packages pass.
- Read-only Python checks: canonical RFC byte parity;4 revision hashes;48 jars;16/70 XML totals;20 genesis per-asset conservation and signed64 opening/expected balances: pass.

Reviewer command limitations: login shell startup emitted unrelated parse error on first Git command; commands rerun with login disabled. Initial guessed platform test path absent; corrected source lookup used platform-runtime. One focused read blocked by consecutive-read hook; graph snippet retrieved instead. Neither failure counted as successful source verification. No Docker operation, Gradle build, retained recorder invocation, fixture rewrite, staging or reviewed-worktree commit. Go command may update external cache; tracked checkout clean.

## Open Questions And Residual Risks

- E0 successful language-server gate still blocked; all six need real successful queries before readiness upgrade. Docker image/guest/topic/runtime gates still pending before E3.
- E1 must unify policy activation naming and fully encode dedup normalized digest/status/context, keyed history transitions and completed coverage. Shape example cannot be copied as complete certified authority.
- Recorder retains output in memory, writes after child close and reserves no atomic ID. Keep serial/bounded use; concurrent/crash-safe runner requires separate hardening if introduced.
- Same-run acceptance reuse and reservation-source lifecycle remain explicit live-integration gaps. No production authority or capacity approval from this pass.
- Historical early relocation compiler output incomplete; checksums certify retained bytes, not reconstructed missing output or execution truth.

## Verdict

**Ready with non-blocking follow-ups** for publishing scoped E0 preparation checkpoint. No new actionable findings; prior P2 byte-capture correction verified. Complete E0 readiness, E1 financial proof and E3 broker proof remain unearned and clearly marked. No heavy pivot or further reviewer dispatch needed from this instance.

## Recommended Next Actions

1. Author may package PR for frozen reviewed scope; preserve blocked-tool and historical provenance wording.
2. Carry full-state schema/dedup/history completion into authorized E1 work before claiming replay authority.
3. Require real six-server and broker prerequisite evidence before upgrading respective gates.

Report remains outside Git in packet directory. No messages to other chats, no new review instances/workstreams. Count2of3.
