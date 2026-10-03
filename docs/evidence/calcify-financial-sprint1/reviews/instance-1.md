# Calcify sprint1 E0 independent review

Review instance: 1 of 3. **Verdict: Ready with non-blocking follow-ups**, scoped to truthful Day1 preparation/prerequisite checkpoint. Full E0 tool-readiness gate still blocked. No kernel, broker recovery, financial authority or capacity approval.

## Findings

### P2 — Preserve output Buffers before writing retained logs

Evidence: `/private/tmp/reef-calcify-financial-e0/scripts/dev/calcify-financial/record-attempt.mjs:18–24`. Both data handlers concatenate Buffer chunks into strings, implicitly decoding each chunk separately. Pipe chunk boundaries can split UTF-8 characters. Bytes `c3 a9` split across two events become `ef bf bd ef bf bd` in retained file, while terminal receives original bytes. Invalid UTF-8/binary output also cannot round-trip through this conversion.

Read-only reproduction of exact accumulation operation:

```js
const chunks = [Buffer.from([0xc3]), Buffer.from([0xa9])];
let captured = '';
for (const chunk of chunks) captured += chunk;
console.log(Buffer.from(captured).toString('hex')); // efbfbdefbfbd
```

Impact: future raw evidence may differ from executed command output; later checksum only certifies altered retained bytes. No proof current committed logs suffered this corruption. Non-blocking for current checkpoint, which exposes its existing evidence limitations and changes no production path.

Smallest correction: accumulate Buffer chunks and write concatenated Buffers, or stream raw bytes to files; keep recorder-generated spawn errors separate from subprocess bytes. Add focused test with split multibyte stdout and stderr. Do not rewrite historical logs. No fix performed.

No P0/P1 findings. No evidence of incorrect frozen economic balances found: gross exchange, cash/security funding repair, one-nano boundary, due-order funding and overflow expectations checked against specified arithmetic. All opening journal groups balance by asset and all opening legs fit signed64.

## Plan Review

Ground truth: clean execution checkout `/private/tmp/reef-calcify-financial-e0`, branch `codex/calcify-financial-sprint1-e0`, base `85f0ce8c84cd7de6bc22322bac0b32da109073ab`, immutable review head `59917fd4fb9cc6dcec6b5295c722eba5e4c5597b`; commits `1a164099`, `59917fd4`. Changed paths match bounded scripts/evidence/work board/inventory scope. Production source unchanged. Primary dirty checkout excluded.

Canonical RFC local copy verified byte-for-byte against planning commit `cda4185b8065bd38126c7547b30fef79a813f68a` (SHA-256 `83a095162705fc461f6c9af1df28002dededeb84f0a530f28b14e486ca279843`). Reviewed identity §3.2, kernel/input/reconstruction §6 and E0/E1 §10.1 alongside execution AGENTS, AI context, documentation map, delivery policy, retention policy and repository steering.

| Requirement | Evidence / assessment |
| --- | --- |
| Six successful language-server source queries | Six raw attempts exist; every response reports shared-manager failure caused by Kotlin initialization. Required success not achieved; README and work board explicitly report blocked gate. Configured-server metadata never substituted for success. |
| Exact-root graph and coverage | Root/head/base verified via index_status; generation `2026-10-03T12:56:58Z`, 14,253 nodes/72,503 edges. Resolver methods queried/snippets read; mergeAcceptance callers/callees traced both ways. Cited source paths show matching metadata and no recorded parse issue. Scripts excluded; direct reads used. |
| Compiler/focused Calcify checks | Clean Java21 build raw log successful; 16 XML suites/70 tests/0 failures/errors/skips confirmed. Existing resolver/unit/topology scope remains explicit. Focused matcher source rerun successful. |
| Frozen financial inputs | 20 cases/52 inputs; byte-stable regeneration; action key, request normalization, selected context, execution scope, units, source causation and kernel-ready fields specified. Independent oracle/evolve correctly deferred. |
| Genesis/history/replay coverage | Per-case opening accounts and journals, separate history/business sequences, staging state, checkpoints, suffix replay, schedules/seeds/crash cuts/mutants specified. Example intentionally abbreviated; full transition encoding remains E1 obligation. |
| Source edges | `service.go:1313` length-frames identities; lifecycle tests at lines70/101/133 cover framing, restore and reuse. `calcify_source_scope_test.go:13` covers modify outcome run and hidden-limit alias. Zero/partial/full IOC tests pass. Resolver mergeAcceptance at lines92–96 conflicts on changed immutable acceptance under same run/order; processor lines137–138 use that key. Live-source restriction correctly retained. |
| Runtime/resources/isolation | Java/runtime/dependency provenance retained; all48 materialized jar hashes match. RF3 Compose validates offline with distinct project/ports/volumes. Guest disk/image digests/live RF3/durability/topic settings remain unverified; manifest flags false/null. No startup needed or performed. |
| Research/measurement | Bounded primary-source claims supported; no performance claim. E4 boundaries and budgets frozen prospectively, not treated as measurements. |
| Delivery/retention | Board links canonical evidence; inventory/checksums cover complete new bundle including failures/corrections. Retention check and9 tests pass. New topic has no superseded sprint bundle requiring archive move. |

Next E1 slice finite and consistent with plan: test-only FinancialKernel/FinancialOracle/FinancialKernelTest; journaled genesis and capture/settle first, frozen cases, independent BigInteger oracle, every-prefix comparisons, full reconstruction/checkpoint suffix, schedules/generated traces and mutation controls before E3. Reservation ownership model remains proposed E1b; live hold activation requires owner acceptance. No need for heavy architecture pivot or expanded workstreams.

Six-server gate remains explicit technical prerequisite. Treat synthetic input preparation as scoped progress, not permission to mark E0 complete. E3 additionally requires actual Docker/guest disk/image/topic verification and adapted harness isolation; existing resolver runner must not execute unchanged.

## Author-Claim Reconciliation

Preliminary ledger saved as `/private/tmp/reef-calcify-e0-review-instance1/PRELIMINARY.md` before opening separate Author Explanation. Necessary evidence README/status text exposed embedded rationale, so first pass was not perfectly blind; implementation and raw failures inspected before separate testimony.

| Author claim | Evidence inspected | Status / consequence |
| --- | --- | --- |
| Current source isolated; production unchanged | Git status/head/branch, exact base..head diff, commit/path list | Confirmed. Review excludes primary edits. |
| Synthetic inputs ready, full E0 tooling blocked | Generator/check result; six raw Serena errors; README readiness table; board | Confirmed within preparation scope; no successful language-server gate. |
| Java21 compilation and70 existing tests pass | Clean build log, attempts JAVA_HOME/head, 16 XML suites | Confirmed retained execution; build not rerun. Does not prove financial kernel or broker EOS. |
| Runtime pinned | Environment, dependency output,48 current jar SHA-256/size checks | Confirmed jar bytes and retained Java/runtime provenance; image/guest runtime still unverified as disclosed. |
| Framing issue stale in planning RFC | Source and regression tests; empty scoped planning..execution source diff | Confirmed. Evidence correction warranted; no speculative source patch needed. |
| Same-run reuse remains unresolved | Lifecycle reuse test; resolver mergeAcceptance; processor orderKey | Confirmed live-source prerequisite, not synthetic gross-DvP blocker. |
| History fixture is shape, full encoding deferred | Generator lines67–86 and expectedFullState disclaimer; RFC E1 acceptance | Confirmed limited claim. Do not use sample as complete replay schema without E1 completion. |
| Unique IDs preserve prior logs | Sequential overwrite-negative retained failure; recorder lines13–24 | Confirmed sequential case only. Non-atomic check permits concurrent same-ID overwrite; author acknowledges concurrent/crash design limitation. UTF-8 byte preservation claim needs finding above. |
| Failures/corrections retained | Attempts, bootstrap relocation/error excerpts, logs, checksums | Confirmed with disclosed early compiler output loss. Successful clean run does not retroactively qualify failed relocation attempt. |
| Primary research supports boundaries | Kafka4.3.1 API/testing/handler, Redpanda transactions, PostgreSQL16 isolation | Confirmed documentary claims, not actual adapter/broker outcomes. |

Kafka commit timeout retry/close rule and application resend limit supported by [KafkaProducer API](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html); test-driver simulation supported by [Kafka testing guide](https://kafka.apache.org/43/streams/developer-guide/testing/). Handler response vocabulary supported by [handler API](https://kafka.apache.org/43/javadoc/org/apache/kafka/streams/errors/StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.html). Topic deletion/remote recovery limits supported by [Redpanda transactions](https://docs.redpanda.com/streaming/current/develop/transactions/). Statement versus transaction snapshots/retry handling supported by [PostgreSQL16 isolation](https://www.postgresql.org/docs/16/transaction-iso.html). These checks support decision boundaries only.

## Verification Performed

Commands run with explicit execution checkout, except Go command with explicit matching-engine subdirectory:

- `git status --short`, `git rev-parse HEAD`, `git branch --show-current`, exact diff/stat/log/path inspection: correct scope, clean before/after.
- `git diff 85f0ce8c84cd7de6bc22322bac0b32da109073ab..59917fd4fb9cc6dcec6b5295c722eba5e4c5597b --check`: pass.
- `node --check scripts/dev/calcify-financial/freeze-fixtures.mjs` and recorder: pass.
- `bun scripts/dev/calcify-financial/freeze-fixtures.mjs --check`: pass;20 cases/52 inputs; SHA-256 `fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.
- `bun run repo:check:records`: pass;423 archived files.
- `bun run repo:test:records`:9/9 pass.
- `node scripts/dev/script-surface-check.mjs`: pass;259 surfaces,247 JavaScript/11 shell checked.
- `shasum -a 256 -c docs/evidence/calcify-financial-sprint1/SHA256SUMS`:84/84 pass.
- `docker compose -p reef-calcify-financial-s1-85f0ce8c -f docs/evidence/calcify-financial-sprint1/broker.compose.yml config --quiet`: pass; offline only.
- `go test ./internal/app ./internal/streamdirect -run 'Test(TradeIdentity|MatchFactsReplaySnapshotAndRollbackExactly|IOCResidualNeverRests|Calcify|ReusedOrderID|SnapshotRestoresReused)' -count=1`: both packages pass.
- Read-only Python checks:48 jar bytes/sizes/hashes, fixture hash,2 tool hashes, opening group conservation/int64 ranges,16 suite XML totals: pass.
- Read-only UTF-8 chunk reproduction: corruption confirmed. Recorder itself not invoked; no retained attempt appended.

Reviewer command correction: three initial `rg` paths mistakenly used repository-relative prefixes while cwd was matching-engine; reads failed, then rerun successfully from exact repository root. Initial guessed graph path `streamdirect/calcify_test.go` absent; corrected to actual `calcify_source_scope_test.go`, read and checked coverage. Neither failed lookup counted as verification. First jar-directory guess absent; actual resolver-probe-deps located and verified.

Serena manual read and exact execution project activated; retained six-query failure evidence inspected, no global repair/onboarding. Graph excluded scripts inspected directly; no exhaustive absence claim. No Gradle rebuild, broker startup, load/fault experiment, fixture rewrite, staging, commit or additional review instance. Go rerun may update external build cache; tracked checkout remains clean. Only review packet files written.

## Open Questions And Residual Risks

- Six language servers have no successful source-query evidence; broker guest/digest/config observations still missing. This checkpoint accurately records blockers rather than satisfying readiness.
- Reconstruction example uses `policies` while owner-field list names `policyActivation`; dedup example omits full digest/status/reference and some keyed transitions. E1 must define concrete full-state schema and encode every required transition before claiming reconstruction. Existing explicit shape disclaimer prevents false E0 proof claim.
- Recorder buffers all output, records attempt only after child close, and does not reserve ID atomically. Keep use serial/bounded until hardened; sequential negative test proves only existing-output refusal.
- Source reuse/reservation lifecycle gaps pre-existing and correctly disclosed. No live financial integration permitted by this review.
- Checksum success establishes committed artifact integrity, not correctness of original capture or historical execution. Early relocation failure remains incompletely logged.

## Verdict

**Ready with non-blocking follow-ups** for Day1 E0 preparation/evidence checkpoint. Implementation small and local, contract expectations actionable for E1, plan deferrals explicit, and author claims largely supported. P2 recorder byte preservation should be fixed before relying on future exact-output evidence. E0 successful tool gate remains blocked; E1 accounting/replay and E3 broker acceptance remain unearned. No heavy pivot decision gate needed.

## Recommended Next Actions

1. Author respond `Accept`, `Dispute with evidence`, or `Defer with owner and rationale` to recorder finding; preserve existing evidence.
2. Retain blocked E0 wording until six real queries succeed; verify Docker prerequisites before E3.
3. In authorized E1 work, complete named policy/dedup/history transitions and independent oracle/replay assertions before upgrading fixture shape to financial proof.

Parent retrieves report; no messages sent to other chats. Review count remains1 of3; no further instances dispatched.
