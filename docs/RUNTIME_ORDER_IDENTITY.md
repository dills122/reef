# Runtime order identity

Runtime order identity is `RuntimeOrderIdentity(runId, orderId)`. Order IDs may
repeat across runs, including runs using the same venue session and instrument.
Blank run ID denotes the legacy namespace; it is never a wildcard in a scoped
lookup. Event IDs remain globally unique immutable fact identities.

Acceptance storage, lifecycle state and lifecycle dirty markers use primary key
`(run_id, order_id)`. Executions, trades, runtime events and command-result rows
carry run ID. Lifecycle aggregates, participant execution ownership and
settlement order attribution join both components. Full rebuild and incremental
projection use the same scope. Canonical records remain separate from these
projection tables; engine matching and Calcify behavior are unchanged.

Single-order lookups without run scope return no order when multiple runs own
that order ID. `/orders/{orderId}` and its `/events` diagnostic route accept
optional `runId`. Their histories use the resolved order's exact identity.
Cancel-by-client-order accepts optional `runId`; a client order ID reused by the
same participant across runs requires it. An ambiguous unscoped lookup returns
not-found instead of selecting the newest run's order.

## Migration 0073

Apply `runtime/0073_runtime_order_run_identity.sql` transactionally with order
writers and projectors stopped. It replaces order-only keys, adds scope to
facts/results, and installs scoped status/timeline/lifecycle projection SQL.
Compat bootstrap packages this exact migration source instead of maintaining a
second copy of its scoped SQL. Direct projection writes atomically invalidate
scoped lifecycle markers. Dirty conflict updates retain the established row
lock and oldest timestamp without rewriting an unchanged marker.

Existing orders retain their stored run and metadata. When local canonical
`runtime.canonical_command_results` snapshots exist, migration restores missing
accepted orders and backfills event/fill/trade ownership by immutable event ID.
It also scopes known command-result rows by command ID. Migration never derives
historical ownership from the current `runtime.orders` row: that row may already
have been overwritten. It queues every recovered acceptance for recomputation.

Records without canonical provenance remain unchanged in the blank-run
namespace. They must not affect a scoped run's fills, cancellation, modification
or settlement attribution. This preserves source data but does not prove those
records belonged to a legacy run. An overwritten acceptance without a retained
canonical snapshot cannot be reconstructed from its former projection row.

## Rollout and reconciliation gate

1. Quiesce writers/projectors. Back up affected tables and retained canonical
   snapshots, captured commands and archive material. Inventory blank-run facts
   whose order ID also appears under nonblank runs.
2. Apply migration inside one transaction. Any SQL failure rolls back that
   transaction; do not resume old writers against a partially changed schema.
3. Reconcile provenance before serving historical or settlement reads. Local
   canonical snapshot recovery can be checked in place. Venue-event-batch or
   external canonical history with retained blank-run facts requires a **fresh
   projection store**, unless ownership has first been repaired from separately
   verified provenance. Replaying scoped facts into existing blank-run rows raises
   an immutable event/result replay conflict; a new consumer frontier alone does
   not repair those rows. Keep conflict rejection enabled.
4. For fresh rebuild, provision an empty projection database with the complete
   current migration chain. Keep `RUNTIME_POSTGRES_JDBC_URL` connected to retained
   canonical/captured-command source; configure an isolated rebuilding worker's
   `RUNTIME_PROJECTION_POSTGRES_JDBC_URL` for new database. Restore required
   reference/profile configuration through normal seed/configuration paths. Do
   not copy legacy orders, fills, trades, events, results or projection watermarks
   into new store. Retain old database and archives as evidence, with backups.
   Replay retained canonical outcomes through normal projector with captured
   scope, matching schema names and a fresh projection name/frontier starting
   before earliest retained fact. Missing submit snapshots require captured
   acceptance fields; cancel/modify outcomes require captured run metadata.
5. Rebuild lifecycle, then refresh market projections in new store. Check
   run-scoped ownership, fills, terminal state, replay counts/frontiers and
   settlement attribution against retained canonical evidence. Cut API readers
   and writers over to verified projection database together, then resume
   consumers. Keep old projection store read-only for comparison; do not merge
   its unproven facts into rebuilt scoped history.
6. Keep records lacking proof in the legacy namespace and report the remaining
   history gap. Operator must decide whether retained canonical/captured/archive
   evidence can repair that gap or whether rollout remains blocked for the
   affected history. Do not guess a run from current participant/account data.

Read-only inventory example:

```sql
SELECT fact.run_id, count(*) AS facts_with_scoped_order_ids
FROM runtime.executions fact
WHERE fact.run_id = '' AND EXISTS (
  SELECT 1 FROM runtime.orders orders
  WHERE orders.order_id = fact.order_id AND orders.run_id <> ''
)
GROUP BY fact.run_id;
```

Apply the same inventory to runtime events and each trade order reference.
Blank run is also valid for explicitly legacy inputs, so a count is a
reconciliation lead, not proof that every row needs reassignment.

## Rollback

Migration is forward-only after commit. Old images containing
`ON CONFLICT (order_id)` cannot write against the composite key. Do not restore
order-only uniqueness or collapse duplicate run identities. Roll back an
application image only to a version supporting the new scoped schema. Restoring
a pre-migration database snapshot requires stopping all writers, preserving
post-snapshot canonical facts, and an operator-approved recovery/replay plan;
it cannot preserve newly distinct run identities in the old schema.

## Evidence

Normal runtime test suite contains cross-run acceptance, direct/bulk/canonical
replay, scoped modify/cancel events, participant fills, settlement selection and
rebuild regressions. Selected Postgres tests require
`RUNTIME_POSTGRES_JDBC_URL_TEST`, `RUNTIME_POSTGRES_USER_TEST`, and
`RUNTIME_POSTGRES_PASSWORD_TEST`. New integration tests report a skipped test
when no database is configured; a skipped test is not SQL qualification.

`PostgresRunOrderIdentityMigrationIntegrationTest` installs pre-0073 SQL in an
isolated schema, reproduces the legacy overwrite, then checks canonical recovery
and retention of an unproven cancellation event without cross-run attribution.

PlatformHttpServer binds configured `CapturedCommandPayloadLookup` source to
in-memory runtime before starting projector loops. Standalone in-memory
projectors bind `capturedCommandPayloadLookup` to their captured command source. Submit snapshots can carry run metadata themselves;
cancel and modify outcomes depend on captured command payloads. Missing payloads
remain in legacy blank-run scope; projectors never infer run from an order ID.

Fresh-store recovery regression also proves scoped replay into upgraded unknown
legacy facts fails, then rebuilds an isolated empty projection schema from
retained canonical snapshots through normal projector. Replay/rebuild retain both
runs while old blank-run facts remain unchanged. Test uses two schemas in one
dedicated DB; production database provisioning/cutover is an unexecuted operator
step, not a qualified deployment.
