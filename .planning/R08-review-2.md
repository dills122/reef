# Independent review 2 of 3

Fresh r08_review_2, read-only. Verdict Not ready. No heavy pivot required.

P1: scoped historical replay into upgraded unknown blank-run facts cannot repair identity; immutable event/result comparison rejects run changes. Migration repairs only local canonical_command_results; external/venue-event history needs proven repair or fresh projection rebuild. Reviewer realPG pre0073+0073 probe raised execution replay conflict fill-review2; rolled back. Docs had directed in-place normal replay incorrectly.
Author response Accept: fresh projection rebuild path documented and tested, immutability retained; old evidence and archives retained, fresh frontiers/captured scope/cutover explicit. Two-schema realPG recovery regression uses retained canonical snapshots and normal projector, not arbitrary reassignment.

P2: memory captured lookup constructor default null; default factory created it without source. Manually injected-map regression only proved converter capability.
Author response Accept: HTTP composition now binds actual configured capture source before projector loops; marker interface payload lookup supported by memory/log/Postgres capture stores. Composition regression default-constructs memory and uses actual capture reservation; covers both plain memory capture and command-log wrapper.

Reviewer verification:20 memory/realPG tests passed,25 Node migrations passed, diff check passed. Full author log inspected but not rerun. Graph stale/source fallback. Blind pass limited because plan ledger included prior review rationale before author packet. Final review3 must use neutral plan separated from evidence.
