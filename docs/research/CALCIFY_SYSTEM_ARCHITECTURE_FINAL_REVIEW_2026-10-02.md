# Calcify system architecture — final independent review

Review instance **3 of3**, final allowed pass. Frozen branch
`codex/calcify-system-architecture`; baseline
`16e15340919bc9330afbbfec0f9b089115f3e57f`; head
`057e6d728c3146ad9570faa053c026418e2ade6a`. Three commits, twelve
documentation/provenance paths. No source/config/schema/accepted ADR changes.
Only untracked author planning files; contents unread. Remote master adds infra
dependency updatef8d905a8. Preliminary ledger recorded before author testimony,
research and prior verdicts. Report condensed from fresh read-only reviewer.

## Findings and verdict

**Ready with non-blocking follow-ups**, scoped to proposed documentation publication.
No actionable architecture or proof-plan finding remains. No new financial runtime
or experiment reviewed. One source-attribution limit below requires qualification.
Review loop stops at3of3; no new instances authorized by this report.

## Plan assessment

- Identity contract separates historySeq from stable business decision/journal/effect
  identity. Decision read view excludes future staging and delivery cursors.
- Continuations assign fixed logical decisions. CPU yields and transaction grouping
  cannot allocate different financial identities.
- Recovery comparison separates exact-history owner-state reconstruction from
  same-admission business equivalence at matched business frontiers.
- P1 tests staged funding between two settlements versus funding after phase,
  duplicate funding, mid-stage restore and transaction regrouping.
- P2 requires result-based recovery after local/changelog loss, then eventual funding
  exactly once. P0–P4 proof order remains credible.
- Closed run ownership, complete validation, gate credits, SQL checkpoints, certified
  recovery and hot-domain capacity remain pre-adoption requirements.

## Claim reconciliation

| Claim | Reviewer assessment |
| --- | --- |
| Staging no longer changes financial IDs | Confirmed contract; implementation unrun |
| Results reconstruct staged work | Explicit nonterminal staging/dequeue; P2 pending |
| Existing relational authority remains active | Confirmed; D-009 unchanged |
| Resolver uses run-scoped framed keys | Confirmed in processor source |
| Execution-ID collisions remain source prerequisite | Confirmed appendMatch concatenation; P0 addresses |
| Financial proofs/capacity passed | No claim; explicitly unrun |
| Current Streaming page contains both recovery exceptions | Reviewer retrieval did not support attribution; version qualification required |

Reviewer verified deletion behavior in
[Cloud transactions](https://docs.redpanda.com/cloud-data-platform/develop/transactions/)
and deletion/remote-recovery caveats in
[Streaming23.3 transactions](https://docs.redpanda.com/streaming/23.3/develop/transactions/).
These do not establish tested26.2.3 behavior. Pinned-version proof remains required.

## Verification

- Git branch/head/base/scope/status match bootstrap; no tracked dirty files.
- Authored diff whitespace passes. Full diff exits2 with16 trailing-space diagnostics
  confined to preserved raw input copies.
- Eight authored Markdown files,47 local file targets/fences: zero failures.
  Anchors/rendered diagrams unverified.
- Three supplied SHA256 values and byte lengths match provenance.
- Codebase Memory generation2026-10-02T05:25:50Z ready; relevant source metadata
  match/no recorded gaps. Changed docs read directly; graph best effort.
- Retained runs confirm failures:8ea6c8ce producer310.730s exceeds301s;
  fa7fec51 rate/backlog fail;53a64664 end gap127,510 exceeds20,000.
- [Kafka EOS](https://kafka.apache.org/43/streams/core-concepts/) and
  [Protobuf fingerprinting](https://protobuf.dev/programming-guides/serialization-not-canonical/)
  support stated boundaries. No runtime tests, load, edits or commits performed.

## Residual uncertainty

Financial reconstruction, adapter atomicity, gate liveness, SQL/API integration,
hot-domain rate and recovery remain unproved. Owner selects authority and numeric
latency/freshness/retention/drain/RTO envelope. Readiness means documentation
publication, not financial adoption.

## Author response after frozen review

Accept version qualification. Dispute categorical current-page absence: author's
fresh `open` and `find` retrieval from current URL returned both caveats withv26.2
navigation, lines105/112; explicit26.2 URL returned error. Retain discrepancy as
source/retrieval uncertainty. Research/RFC now cite explicit Cloud/legacy sources
and avoid inferring deployed26.2.3 guarantees. No architecture/behavior change.

Final publication adds this report and attribution qualification only. All three
review reports retained; architecture correction remains reviewed at057e6d72.
