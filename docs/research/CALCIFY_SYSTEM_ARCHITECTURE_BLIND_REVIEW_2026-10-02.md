# Calcify system architecture — full blind review

Frozen target: `16e15340` → `a54895bf1b55c28470d4366a5b9c92b1c7499b5a`. Reviewer report below retained before author corrections; source links reference reviewed commit. No runtime implementation reviewed.

Review instance: **2 of 3**. Blind preliminary finding recorded before reading author testimony, research reconciliation, prior review, or supplied proposals. Read-only maintained.

## Findings

**P2 — Separate staging-history sequence from stable financial identity.**

[RFC identity table](https://github.com/dills122/reef/blob/a54895bf1b55c28470d4366a5b9c92b1c7499b5a/docs/work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#L107) derives journal/effect IDs from decision sequence. [Continuation contract](https://github.com/dills122/reef/blob/a54895bf1b55c28470d4366a5b9c92b1c7499b5a/docs/work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#L368) adds `InputStaged` records only when consumption advances past unfinished work; immediate inputs skip staging. [P1](https://github.com/dills122/reef/blob/a54895bf1b55c28470d4366a5b9c92b1c7499b5a/docs/work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#L508) requires invariance across CPU yield budgets.

Same admitted `ClockAdvanced → FundAccount` history, with two due settlements:

| Processing schedule | Permitted result history |
|---|---|
| Phase finishes before funding consumption | Clock¹ → settlement A² → settlement B³ → funding⁴ |
| Funding consumed during yielded phase | Clock¹ → settlement A² → InputStaged³ → settlement B⁴ → funding/dequeue⁵ |

Both preserve settlement-before-funding order. Decision sequences, journal/effect IDs, subsequent prior-sequence fields, and semantic digests still differ. Recording staging fixes recovery completeness; it does not establish same-admission decision identity.

**Consequence:** replay comparison contract ambiguous; P1 can preserve economics yet fail declared identity/next-decision invariance. Supporting material does not close this gap.

**Smallest correction:** define separate delivery-history and deterministic business identities. Derive journal/effect IDs from stable action/attempt/continuation identity plus ordinal; specify which state must match across schedules. Alternatively, prescribe canonical staging/continuation record order. Add both schedules, duplicate funding, and restore between staging/dequeue to P1 acceptance fixtures.

No P0/P1 finding established for proposed documentation. Correction remains within existing architecture and proof scope.

## Plan Review

P0→P4 ordering sound:

- **Source foundation:** authoritative run scope verified. Lifecycle/coverage extension correctly treats zero-trade outcomes as required inputs.
- **Financial ownership:** isolated run domain and transitive resource closure prevent false account/instrument sharding assumptions. Unsupported executions remain recorded.
- **Kernel:** whole-decision validation, exact asset units, reservation state, typed insufficiency, and independent accounting oracle provide credible first proof.
- **Broker adapter:** mutation-boundary failures, multiple decisions per transaction, stale owners, and committed staging reconstruction target meaningful failure cases.
- **Gate:** durable credits and reserved completion capacity address prior closure problem. Candidate algorithm remains unproven; P3 adversarial burst/restore test required.
- **Reads/recovery:** SQL checkpoint transaction, consistent financial bundle, certified cuts, retention refusal, and single-writer cutover fit stated invariants.
- **Capacity:** hot shared-account domain precedes aggregate scaling. Equivalent Postgres comparison remains credible fallback.

Managed stack fits existing implementation footprint. Broker EOS supplies Kafka input/state/output coordination; financial rules and external effects require separate contracts. [Kafka documentation](https://kafka.apache.org/43/streams/core-concepts/) supports this boundary. Redpanda’s documented deletion/remote-recovery exceptions justify coordinated restoration requirements. [Redpanda transactions](https://docs.redpanda.com/streaming/current/develop/transactions/)

Future allocation, affirmation, novation, netting, partial settlement, and external finality remain capability contracts—not completed functionality. Appropriate for incremental proposal.

## Author-Claim Reconciliation

| Claim | Evidence / assessment | Status |
|---|---|---|
| #461 fixes resolver run scope | `TradeSourceV1.run_id`, framed lookup keys, both-side checks, namespace/restore fixtures inspected | Confirmed |
| Relational authority remains active | D-009 and normative steering unchanged; RFC requires adoption decision | Confirmed |
| Results cover staged future work | `InputStaged`, pending dedup, dequeue semantics now specified | Contract confirmed; implementation unverified |
| Continuations preserve deterministic decisions | Optional staging changes decision-derived identities | Contradicted as written |
| Gate retains closure path under pressure | Credit/window rules specified; no new proof results | Unverified candidate |
| Historical 10.16k qualifies targets | Raw result fails producer-duration gate | Correctly rejected |
| Postgres remains credible alternative | Same semantics/admission order required; no inherent SQL ceiling asserted | Supported |
| Specialized ledger removes workflow bridge risk | Research explicitly retains bridge/reconciliation risk | Correctly rejected |
| New financial proofs ran | No runtime implementation or new financial evidence claimed | Confirmed scope |

Protobuf fingerprint guidance correctly separates normalized semantics from serialized bytes. [Protobuf documentation](https://protobuf.dev/programming-guides/serialization-not-canonical/) Financial technology comparisons remain architectural judgments, not Reef performance evidence.

## Verification Performed

Frozen target verified:

- Worktree: `/private/tmp/reef-calcify-system-architecture`
- Branch: `codex/calcify-system-architecture`
- Base: `16e15340919bc9330afbbfec0f9b089115f3e57f`
- Head: `a54895bf1b55c28470d4366a5b9c92b1c7499b5a`
- Commits: `2506de63…`, `a54895bf…`
- Dirty state unchanged: only untracked author planning directory; contents unread.

Checks/results:

- `git status --short --branch`, `git rev-parse HEAD`, `git log`, exact base..head diff: match bootstrap; 11 documentation paths.
- Source/config/schema/accepted-ADR diff: empty.
- Authored-document `git diff --check`: pass.
- Full diff whitespace check: exit 2; 16 retained trailing-whitespace diagnostics confined to two supplied input copies.
- Three input SHA256 values and byte lengths: match provenance manifest.
- Authored Markdown local file targets and fence balance: pass; anchors/rendering unverified.
- Codebase Memory: requested generation/root/head match; ten cited paths and three source scopes report no recorded coverage issue. Focused source reads supplement thin class-level traces. Best-effort index caveat retained.
- Historical raw results independently inspected:

| Run | Observed rate | Failure |
|---|---:|---|
| `8ea6c8ce` | 10,160.50 contexts/s | Producer 310.730s exceeded 301s limit |
| `fa7fec51` | 9,881.14 contexts/s | Rate, end-gap, covering-gap gates |
| `53a64664` | 10,605.80 contexts/s | End gap 127,510 exceeded 20,000 |

Exact full-context reconciliation does not overturn timed failures. New financial path has no measured capacity.

No builds, runtime tests, load tests, broker changes, edits, commits, or PR publication performed.

## Open Questions And Residual Risks

Owner decisions remain explicit: financial authority, account sharing, replay promise, finite history/outage budget, and numeric latency/freshness/drain/RTO envelope.

Pre-existing source prerequisites remain distinct from proposal defects:

- Same-run order reuse after matcher retention conflicts with immutable resolver acceptance lifetime.
- [Matcher execution-ID construction](https://github.com/dills122/reef/blob/a54895bf1b55c28470d4366a5b9c92b1c7499b5a/services/matching-engine/internal/app/service.go#L1059) permits delimiter collisions; repeated-fill identity also needs proof.
- Missing consumer checkpoint still requires explicit genesis versus partial-history policy.

RFC P0 addresses these prerequisites. Adoption must also reconcile data-platform steering with chosen authority.

## Verdict

**Ready with non-blocking follow-ups**, scoped to proposed architecture/research documentation.

## Recommended Next Actions

Close staging/identity contract before freezing P1 fixtures. Retain existing proof order and stop conditions. No architecture pivot or additional workstreams warranted by reviewed evidence. All financial adoption and capacity gates remain open.

## Author response after review

Accept P2 staging/identity finding. RFC separates historySeq from canonical businessSeq and stable decision/action/attempt/continuation IDs. Journal/effect IDs and business semantic digest exclude delivery sequence. Business rules cannot inspect future staging/cursors. Existing-history recovery checks full exact owner state; recompute checks canonical business semantics at equivalent business frontiers. Added both clock/funding schedules, duplicate funding and mid-stage restore to P1. Architecture/proof order unchanged. Third and final fresh pass will check corrected contract; no verdict from that pass claimed here.
