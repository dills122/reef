# Author explanation — Calcify system architecture research

Embargo for blind review until preliminary findings recorded. Proposed docs,
not financial implementation. Review instance2of3 follows first frozen review.

## Intent and success criteria

Review three supplied proposals against current master, research financial
authority/runtime alternatives, and craft complete post-trade plan with finite
proofs. Preserve incremental engineering and source-only Phase1/2 boundaries;
no production architecture accepted from a documentation review.

## Plan-to-artifact traceability

| Requirement | Artifact |
| --- | --- |
| Correct prior assumptions | Research section1 and proto README identity paragraph |
| Primary technology research | Research sections2–3, direct maintainer/standards links |
| Full architecture and wiring | RFC sections2–9 with flow/state diagrams |
| Finite incremental experiments | RFC section10 P0–P4 plus qualification |
| Authority/adoption decisions | RFC1/11; accepted D-009 unchanged |
| Independent critique / response | Independent report on frozen2506de63 plus response ledger |
| Source provenance | Three exact supplied copies, byte lengths/SHA256 |

## Technical approach and changed components

Existing source/log/resolver retained. Candidate Kotlin pure decide/evolve kernel
under managed Kafka Streams/RocksDB/Redpanda; complete financial decisions, independent
SQL bundle and progress, effect-intent protocol, optional archive with mandatory
finite history coverage. Financial resources initially closed to one run; one hot
domain measured before sharding. DB transaction/outbox credible alternative;
specialized ledger requires bridge/time proof. Other runtimes need measured fit gap.

Docs only. New research/RFC/source copies/report/handoff, docs index link and one
stale identity paragraph correction. No runtime/schema fields/config change,
accepted ADR amendment, source fix or benchmark qualification.

## Decisions and rejected alternatives

Managed stack best first candidate by existing footprint and state/input/output
commit. It supplies neither financial model nor tested10k rate. Rejected universal
SQL-too-slow premise; compare equivalent shared-account semantics if needed. New
consensus/workflow/ledger service not justified without specific failed requirement.

## Invariants and boundary conditions

Explicit physical authority; durable input before acknowledgement; closed financial
resources; validate whole decision before mutations; abort unexpected adapter error;
exact per-asset accounting; pending execution preserved; result-complete future state;
stable business IDs independent of locator; canonical logical work ordering;
certified cuts retain staged work; no historical effect dispatch or output re-emission.

First reviewer found bounded clarifications. Corrected RFC records unfinished input
via nonterminal InputStaged; later dequeue/business completion separate. Gate grants
durable window credits with full completion/seal reservation; uncredited work stays
upstream. Both require real reconstruction/liveness experiments. P0 explicitly
tests concatenated execution-ID collisions. No financial/runtime repair claimed.

## Verification performed and limits

Changed-doc local path/fence checks and exact three supplied input SHA256 pass.
Authored-doc diff whitespace check separated from full diff: exact supplied copies
retain pre-existing trailing spaces. Diagrams inspected as Mermaid source only.
No runtime/throughput experiment executed. Reviewer1 inspected historical logs and
primary sources with no inherited conversation; report retains exact frozen target.

Current source baseline16e15340 includes #461 authoritative run and framed order
lookup; same-run lifetime and collision-safe execution identity remain source
prerequisites. Codebase Memory generation2026-10-02T05:25:50Z full graph, focused
coverage checks/source reads; graph best effort. No entire codebase proof implied.

## Costs, deviations and open decisions

Potential single hot market/run domain; durable admission/result boundaries;
projection lag/WAL; managed native state/restore cost; complete semantic evolution
and schema/version management. P0–P4 proposed and unrun. Owner selects physical
authority, sharing/replay promises and numeric latency/history/recovery envelope.
Seed-only replay depends on simulator observation closure. Full business workflow,
netting, external/legal finality and legacy cutover remain later capabilities.

## Challenge points

Full-state evolution and recovery cuts, gate credit liveness under legal interleavings,
canonical continuations and producer observations, shared-account domain capacity,
SQL/API coupled progress and evidence vs claims. Review unrestricted within target;
do not limit findings to these prompts or accept prior verdict as evidence.
