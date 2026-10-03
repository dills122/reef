# Calcify system architecture — independent review and response

Review instance: **1 of3**. Fresh context, read-only. Frozen target:
`16e15340919bc9330afbbfec0f9b089115f3e57f` →
`2506de63e002b2978b60e3adde01665557bd3cdd`, branch
`codex/calcify-system-architecture`. Scope: proposed architecture/research docs,
not implemented financial runtime. Reviewer recorded preliminary blind findings
before reading author explanation, research reconciliation and supplied inputs.
Line references below identify frozen RFC, not subsequently corrected line numbers.

## Findings (reviewer)

### P2 — Record input staging in reconstructible authority

RFC line345 stages later inputs during logical work phase. Lines88–90/291–295
require results to reconstruct all future state, but staging authority record and
dedup disposition were unspecified.

Scenario: clock opens phase; funding enters managed queue; transaction commits
staging and consumed offset; changelog unavailable. Result-only `evolve` cannot
recover funding unless staging produced complete authority delta. Treating staging
as completed action could instead suppress eventual funding.

Smallest fix: `InputStaged` or equivalent delta whenever committed checkpoint
advances past unfinished input; envelope/reference, order and nonterminal status;
later business completion preserved. Reconstruct after staging commit before phase
completion.

### P2 — Bound completion traffic for unopened windows

RFC line215 separates headers/completion traffic and reserves admitted capacity.
Unopened traffic lacked explicit producer credit/FIFO/lookahead bound.

Scenario: A admitted; headers paused at high water. Future B/C completions precede
A seal on completion partition. Retaining them exhausts capacity; pausing prevents
A closure. Separate channels alone do not remove deadlock.

Smallest fix: producer window credits/FIFO membership, or proven bounded lookahead
with separately reserved capacity. P3 fixture includes future-window burst, bounded
progress and restore.

Neither requires architecture pivot. Both clarify existing mandatory proofs;
neither establishes defect in implemented financial runtime.

## Plan review (reviewer)

P0–P4 order sound: source contracts, pure accounting, broker atomicity, useful API
path, hot-domain capacity. Qualification separate. Whole-run closure, source
causality, conservation, failure aborts, SQL checkpoints, retention refusal and
single-writer cutover addressed.

Add explicit P0 execution-ID collision fixture. Baseline matcher
`services/matching-engine/internal/app/service.go:1061` concatenates unescaped IDs;
`("a-b","c")` and `("a","b-c")` collide at equal ordinal. Pre-existing source
issue owned by RFC's identity audit.

## Author-claim reconciliation (reviewer)

| Claim | Assessment |
| --- | --- |
| #461 fixes resolver run scope | Confirmed authoritative trade run, framed keys, both-side checks |
| Existing relational authority remains pending adoption | Confirmed; accepted ADR unchanged |
| New financial proofs unrun | Confirmed; no qualification overclaim |
| Results reconstruct all future state | Required; staging clarification still needed |
| Gate closes under pressure | Correctly labelled candidate; unopened-window bound needed |
| Historical10.16k qualifies targets | Correctly rejected: producer310.730s exceeded301s gate |
| Diff whitespace passed | Must qualify scope; full frozen diff fails on retained inputs |

Primary-platform claims checked against
[Kafka](https://kafka.apache.org/43/streams/core-concepts/),
[Redpanda](https://docs.redpanda.com/streaming/current/develop/transactions/),
[Protobuf](https://protobuf.dev/programming-guides/serialization-not-canonical/),
[Postgres](https://www.postgresql.org/docs/current/transaction-iso.html) and
[TigerBeetle](https://docs.tigerbeetle.com/coding/linked-events/).
SQL comparison credible; no universal SQL capacity rejection. Specialized ledger
remains conditional with bridge/time risks.

## Verification (reviewer)

- Branch/base/head/scope verified; dirty state only untracked author planning.
- Core RFC, proto README and docs-index diff whitespace check passes.
- Full frozen diff whitespace check fails only preserved input trailing spaces
  (`initial-review.md`, `review-addendum.md`); preserve exact supplied bytes.
- Three input SHA256/lengths match provenance.
- Codebase Memory generation2026-10-02T05:25:50Z; eight cited source/contract paths
  metadata match, relevant queries paginated, focused snippets read. Best effort.
- Historical sustained/short artifacts inspected. No new runtime/benchmark tests.
- Preliminary findings recorded before author/testimony material; read-only maintained.

## Risks and verdict (reviewer)

Owner still selects authority, numeric retention/RTO/freshness and hot-domain
workload. All new proofs unrun; workflow parity/external finality deferred.

**Ready with non-blocking follow-ups**, for proposed research/RFC documentation.
No P0/P1 or heavy pivot established. Verdict does not accept financial architecture
or approve implementation. Next fresh pass targets corrected head, instance2of3.

## Finding response (author, after frozen review)

| Finding | Disposition | Correction / evidence |
| --- | --- | --- |
| Staging authority | Accept | RFC6.4 specifies committed `InputStaged`, pending dedup, dequeue/business completion; P2 result reconstruction fixture |
| Completion pressure | Accept | RFC5.2 specifies durable window credits, retained uncredited history, full completion/seal budgets, duplicate/restore/epoch handling; P3 burst/restore fixture |
| Execution ID collision | Accept | RFC3.2/P0 explicitly require framing, collision, repeated-fill/modify/restore tests; runtime fix still unimplemented |
| Whitespace claim scope | Accept | Authored-doc diff check separated from byte-exact input copies. Copies preserved and checksummed. |

Corrections amend candidate contracts, not business scope. A separate owner-requested
blind chat will assess corrected full plan without this report or author rationale
in its initial pass. No second-review verdict claimed here.
