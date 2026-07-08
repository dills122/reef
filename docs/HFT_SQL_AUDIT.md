# HFT SQL Audit

This audit covers the SQL migration shape under `scripts/dev/db/migrations/` for Reef's finance/HFT venue use case.

## Current verdict

The schema direction is sound for a simulation-first trading venue: command intake is durable, idempotency keys are scoped, canonical command outcomes are append-oriented, projection watermarks are explicit, and hot-path write amplification has been reduced deliberately.

The remaining gaps are mostly production-hardening gaps, not evidence that the SQL is unusable. The highest-risk items require coordinated service and mapper work because public API payloads can remain string-shaped while database facts become typed.

## Findings

### 1. Typed finance facts remain text

Priority: P1

Affected areas:
- `runtime.orders`
- `runtime.executions`
- `runtime.trades`
- `runtime.runtime_events`
- `runtime.submit_results`
- `runtime.order_lifecycle_state`
- `runtime.market_data_snapshots`
- `runtime.canonical_venue_event_batches`
- `runtime.canonical_command_results`
- `settlement.obligations`
- `boundary.instrument_price_collars`
- `boundary.account_risk_controls`
- `boundary.account_risk_decisions`
- `boundary.boundary_rejections`

Price, quantity, timestamp, and some ID facts are still stored as `TEXT`. That blocks database-level scale/range checks, creates cast-heavy queries, and makes ordered market-data access depend on expression indexes or late casts.

Target:
- Use `UUID` for stable UUID-shaped identifiers.
- Use `TIMESTAMPTZ` for event and acceptance times.
- Use `NUMERIC(p,s)` where exact decimal storage matters and throughput is acceptable.
- Prefer fixed-point `BIGINT` tick/minor-unit columns where the instrument contract gives a stable scale and hot-path comparisons dominate.
- Keep raw JSON/text payloads as evidence and compatibility edges, not the only queryable fact.

### 2. Runtime event schema regressed from typed to text

Priority: P1

`runtime/0002_event_backbone.sql` defines `runtime.runtime_events.event_id` as `UUID` and `occurred_at` as `TIMESTAMPTZ`. `runtime/0003_live_runtime_persistence.sql` changes both to `TEXT` for live compatibility.

Target:
- Pick one canonical runtime event schema.
- Restore typed event identity/time in a forward migration.
- Update Kotlin bootstrap validation, mappers, replay queries, and event save/read tests in the same slice.

Implemented here:
- `runtime/0029_typed_runtime_event_facts.sql` adds typed `event_id_uuid` and `occurred_at_ts` companion columns while preserving the existing text columns.
- A `runtime.runtime_events_set_typed_facts()` trigger backfills those typed facts for new event inserts from both Kotlin and database persistence routines.
- Recent-event reads now prefer native timestamp ordering with deterministic text/id fallback.

### 3. High-volume append tables are not physically partitioned

Priority: P1

Affected areas:
- `command_log.commands`
- `command_log.command_payloads`
- `command_log.command_results`
- `runtime.canonical_venue_event_batches`
- `runtime.canonical_command_outcomes`
- `runtime.runtime_events`
- `runtime.trades`

Plain append tables are fine for the current local stack, but HFT volume will need partition-aware retention, vacuum isolation, and replay windows.

Target:
- Partition terminal/canonical history before mutating command intake in place.
- Prefer market date, venue session, run, event stream, or command partition keys depending on the table.
- Keep active queue tables small instead of partitioning active scheduling state.

### 4. JSONB is carrying canonical facts that should also be typed

Priority: P2

`runtime.canonical_command_outcomes.result_payload` and batch payload JSON preserve replay evidence, which is good. The issue is relying on JSON extraction for hot audit/query fields.

Target:
- Preserve raw payloads.
- Promote stable query-critical facts to typed columns or generated stored columns.
- Keep projector SQL from depending on command-log payload fallback when event batches already carry the needed projection facts.

### 5. Hot-path FK removal needs replacement audit checks

Priority: P2

`command_log.command_payloads`, `command_log.command_work_queue`, and `command_log.command_results` intentionally dropped same-schema FKs to remove hot insert/delete checks.

Implemented here:
- `command_log/0014_integrity_audit_views.sql` adds `command_log.command_integrity_violations`.
- `command_log.command_integrity_summary()` groups those violations for smoke, CI, or operator checks.
- `scripts/dev/command-log-prune.mjs` deletes orphan child rows when `DEV_COMMAND_LOG_PRUNE_APPLY=1`.

The checks cover orphan payloads, orphan queue rows, orphan results, active commands missing reconstructed queue state, and terminal results that still have active queue rows.

### 6. Unlogged active queue needs crash-recovery accounting

Priority: P2

`command_log.command_work_queue` is unlogged because it is derived active scheduling state. That is a reasonable performance trade, but it must stay paired with logged accepted commands, logged terminal results, and a reconstruction/invariant check after crash or unclean shutdown.

Implemented here:
- The new command-log audit view exposes active commands missing queue rows.
- The command-log prune utility can now remove orphan child rows surfaced by that view.

Still needed:
- Wire this into a smoke or operational check after queue reconstruction.

### 7. Two canonical command models exist

Priority: P2

Both `runtime.canonical_command_results` and `runtime.canonical_command_outcomes` exist. The current hardening protects immutability, but the names and overlap can confuse audit consumers.

Target:
- Mark one path as legacy/compat or collapse callers onto the newer venue-event-batch materialization model.
- Keep command status APIs clear about preferred canonical source and fallback order.

### 8. Top-of-book numeric index is transitional

Priority: P3

`runtime/0027_audit_persistence_hardening.sql` adds a numeric expression index over text `limit_price`. This improves query shape without a breaking type migration, but it is not the final HFT form.

Target:
- Move price and remaining quantity to typed columns.
- Add side-aware indexes that satisfy best bid/ask `ORDER BY ... LIMIT 1` patterns without regex filters or casts.

Implemented here:
- `runtime/0028_typed_top_of_book_facts.sql` adds typed numeric companion columns for lifecycle book facts and top-of-book snapshots.
- The lifecycle and market-data projection routines now maintain those numeric facts while preserving existing text columns for API compatibility.
- Native bid/ask indexes on `runtime.order_lifecycle_state` replace the transitional text-cast expression index for top-of-book access.

### 9. Boundary risk/collar facts need typed constraints

Priority: P3

Risk limits, collar prices, rejection quantities, and related notional facts are currently string-shaped.

Target:
- Add typed price/notional/quantity columns and constraints for risk-critical checks.
- Add currency validation once instrument/currency contracts are stable.

### 10. `CREATE TABLE IF NOT EXISTS` can hide drift

Priority: P3

Forward migrations use `IF NOT EXISTS` widely. The migration ledger and validation tests reduce risk, but PostgreSQL does not guarantee an existing relation matches the requested shape.

Target:
- Continue moving toward migration-owned schema and validate-mode startup.
- Narrow compatibility bootstrap over time.
- Add schema validation for high-risk column types as typed migrations land.

## Work split

### Done in this slice

- Preserved first canonical command-result writes and conflict detection in `runtime/0027_audit_persistence_hardening.sql`.
- Added numeric top-of-book expression index in `runtime/0027_audit_persistence_hardening.sql`.
- Added command-log integrity diagnostics in `command_log/0014_integrity_audit_views.sql`.
- Added orphan-child cleanup to `scripts/dev/command-log-prune.mjs`.
- Added typed top-of-book projection facts and native lifecycle book indexes in `runtime/0028_typed_top_of_book_facts.sql`.
- Added typed runtime event identity/time companion facts and native recent-event indexes in `runtime/0029_typed_runtime_event_facts.sql`.
- Documented typed facts, event schema, partitioning, JSONB, FK, unlogged queue, canonical model, and boundary-risk follow-ups.

### Next service-spanning work

- Typed runtime facts beyond top-of-book and runtime event companion columns, across persistence, bootstrap validation, mappers, projectors, and smoke tests.
- Final runtime event schema reconciliation once the compatibility text surface can be retired.
- Physical partition plan and measured migration path.
- Canonical command model cleanup.
- Risk/collar typed constraints.
