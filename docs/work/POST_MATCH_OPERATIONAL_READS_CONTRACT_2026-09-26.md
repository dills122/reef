# Post-match operational reads — 2026-09-26

Status: read adapter for parity work; public routes still use the existing
projection. See the [execution plan](POST_MATCH_SCALING_IMPLEMENTATION_PLAN_2026-09-26.md).

`PostMatchOperationalReadStore` reads only the isolated post-match database.
Every query requires an explicit event stream and source generation, scopes
rows by participant, binds optional instrument/run filters, and caps output at
500 rows. Own-order state joins the canonical order directory to recover
ownership and side. Fills join the same directory for participant, side, and
run identity. The order status `ACCEPTED` maps to the existing public `OPEN`
value. No read falls back to a row from another generation or participant.

Rows and the live consumer's per-partition frontiers are read from one
repeatable-read snapshot. The frontier map is an **as-of token**, not proof of
source catch-up. An absent partition frontier is not represented as complete.
Before enabling a public route, compare source and target generations,
partition coverage and lag, authorization behavior, field values and ordering,
and aged/hot-participant query work. Preserve the legacy route as rollback
until the same cohort passes parity and recovery checks.

The current adapter preserves the old response fields for orders and fills.
PostgreSQL numeric and timestamp formatting may differ from legacy stored
strings; compare business values and decide the wire representation before
cutover. Market snapshot routing also needs explicit run/session aggregation
semantics because the new market state is keyed by run and venue session.
