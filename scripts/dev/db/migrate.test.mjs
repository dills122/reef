import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { buildApplySql, discoverMigrations, migrationsForTarget, validateMigrationOrder } from "./migrate.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const migrationsRoot = path.resolve(__dirname, "migrations");

test("discovers deterministic domain migrations", async () => {
  const migrations = await discoverMigrations(migrationsRoot);

  validateMigrationOrder(migrations);
  assert.ok(migrations.length >= 7);
  assert.deepEqual(
    migrations.map((migration) => migration.id).filter((id) => id.startsWith("runtime/")),
    [
      "runtime/0001_runtime_init.sql",
      "runtime/0002_event_backbone.sql",
      "runtime/0003_live_runtime_persistence.sql",
      "runtime/0004_bulk_submit_outcomes.sql",
      "runtime/0005_set_based_submit_outcomes.sql",
      "runtime/0006_canonical_append_store.sql",
      "runtime/0007_projection_watermarks.sql",
      "runtime/0008_partitioned_projection_batching.sql",
      "runtime/0009_runtime_canonical_event_row_toggle.sql",
      "runtime/0010_venue_event_batch_materialization.sql",
      "runtime/0011_canonical_command_outcome_projection.sql",
      "runtime/0012_cap_command_outcome_projection_batch.sql",
      "runtime/0013_scope_venue_event_batch_identity.sql",
      "runtime/0014_lifecycle_command_outcome_projection.sql",
      "runtime/0015_market_data_snapshots.sql",
      "runtime/0016_order_lifecycle_state.sql",
      "runtime/0017_project_execution_trade_fills.sql",
      "runtime/0018_projector_fill_depth_toggle.sql",
      "runtime/0019_reject_order_lifecycle.sql",
      "runtime/0020_order_lifecycle_incremental.sql",
      "runtime/0021_market_data_snapshot_incremental.sql",
      "runtime/0022_trade_tape.sql",
      "runtime/0023_project_orders_from_event_batch_payload.sql",
      "runtime/0024_scope_command_outcome_projection_stream.sql",
      "runtime/0025_orders_client_order_lookup.sql",
      "runtime/0026_contiguous_command_outcome_projection_watermarks.sql",
      "runtime/0027_audit_persistence_hardening.sql",
      "runtime/0028_typed_top_of_book_facts.sql",
      "runtime/0029_typed_runtime_event_facts.sql",
      "runtime/0030_typed_submit_result_facts.sql",
      "runtime/0031_typed_execution_trade_facts.sql",
      "runtime/0032_typed_order_facts.sql",
      "runtime/0033_typed_canonical_time_facts.sql",
      "runtime/0034_post_trade_profile_references.sql",
      "runtime/0035_drop_batch_payload_gin_lookup_index.sql",
      "runtime/0036_canonical_archive_tables.sql",
      "runtime/0037_runtime_event_trade_archive_tables.sql",
      "runtime/0038_projection_dirty_lock_order.sql",
      "runtime/0039_command_outcome_projection_metadata.sql",
      "runtime/0040_split_submit_outcome_projection_stages.sql",
      "runtime/0041_deterministic_timeline_projection_sequence.sql",
      "runtime/0042_unlogged_projection_dirty_queues.sql",
      "runtime/0043_runtime_event_payload_cold_table.sql",
      "runtime/0044_idempotent_lifecycle_projection.sql",
      "runtime/0045_drop_legacy_runtime_event_indexes.sql",
      "runtime/0046_order_modified_lifecycle_index.sql",
    ],
  );
  assert.ok(migrations.some((migration) => migration.id === "admin/0002_post_trade_profiles.sql"));
  assert.ok(migrations.some((migration) => migration.id === "auth/0002_live_auth_tables.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0002_live_boundary_tables.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0003_command_capture_live_shape.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0004_command_capture_legacy_defaults.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0006_account_risk_controls.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0007_command_circuit_breakers.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0009_instrument_price_collars.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0010_boundary_rejections.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0008_account_risk_limits.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0001_commands.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0002_command_results.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0003_queue_result_split.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0004_terminal_results_active_queue.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0005_result_terminal_metadata.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0006_command_append_function.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0007_retention_pins.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0008_command_append_queue_timestamp.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0009_run_metadata.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0010_drop_legacy_status_index.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0011_unlogged_active_queue.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0012_command_payloads.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0013_drop_hot_path_foreign_keys.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0014_integrity_audit_views.sql"));
  assert.ok(migrations.some((migration) => migration.id === "command_log/0015_command_results_archive.sql"));
  assert.ok(migrations.some((migration) => migration.id === "settlement/0001_p2_exception_facts.sql"));
  assert.ok(migrations.some((migration) => migration.id === "settlement/0006_allocation_confirmation_affirmation_facts.sql"));
  assert.ok(migrations.some((migration) => migration.id === "arena/0001_arena_registry.sql"));
  assert.ok(migrations.some((migration) => migration.id === "analytics/0001_simulation_run_exports.sql"));
  assert.ok(migrations.some((migration) => migration.id === "analytics/0002_run_bot_performance_summaries.sql"));
});

test("audit hardening migration preserves first command outcome and counts actual canonical inserts", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0027_audit_persistence_hardening.sql");

  assert.ok(migration);
  assert.match(migration.sql, /ON CONFLICT \(command_id\) DO UPDATE SET\s+command_id = runtime\.submit_results\.command_id/);
  assert.match(migration.sql, /runtime\.submit_results\.occurred_at = EXCLUDED\.occurred_at/);
  assert.match(migration.sql, /SELECT COUNT\(\*\) INTO appended_count FROM insert_results/);
  assert.match(migration.sql, /idx_order_lifecycle_state_book_numeric_price/);
});

test("command-log integrity audit migration replaces dropped hot-path foreign key checks", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "command_log/0014_integrity_audit_views.sql");

  assert.ok(migration);
  assert.match(migration.sql, /CREATE OR REPLACE VIEW command_log\.command_integrity_violations/);
  assert.match(migration.sql, /orphan_payload/);
  assert.match(migration.sql, /active_command_missing_queue/);
  assert.match(migration.sql, /terminal_result_still_queued/);
  assert.match(migration.sql, /CREATE OR REPLACE FUNCTION command_log\.command_integrity_summary/);
});

test("typed top-of-book migration adds native numeric facts and indexes", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0028_typed_top_of_book_facts.sql");

  assert.ok(migration);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS limit_price_num NUMERIC/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS best_bid_price_num NUMERIC/);
  assert.match(migration.sql, /idx_order_lifecycle_state_book_bid_native/);
  assert.match(migration.sql, /idx_order_lifecycle_state_book_ask_native/);
  assert.match(migration.sql, /limit_price_num AS price_num/);
  assert.match(migration.sql, /best_bid_price_num = EXCLUDED\.best_bid_price_num/);
});

test("typed runtime event migration adds native UUID and timestamp facts", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0029_typed_runtime_event_facts.sql");

  assert.ok(migration);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS event_id_uuid UUID/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /CREATE OR REPLACE FUNCTION runtime\.runtime_events_set_typed_facts/);
  assert.match(migration.sql, /CREATE TRIGGER runtime_events_set_typed_facts/);
  assert.match(migration.sql, /idx_runtime_events_occurred_typed/);
  assert.match(migration.sql, /idx_runtime_events_order_occurred_typed/);
});

test("typed submit result migration adds native audit facts", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0030_typed_submit_result_facts.sql");

  assert.ok(migration);
  assert.match(migration.sql, /ALTER TABLE runtime\.submit_results/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS event_id_uuid UUID/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /CREATE OR REPLACE FUNCTION runtime\.submit_results_set_typed_facts/);
  assert.match(migration.sql, /CREATE TRIGGER submit_results_set_typed_facts/);
  assert.match(migration.sql, /idx_submit_results_occurred_typed/);
  assert.match(migration.sql, /idx_submit_results_event_uuid/);
});

test("typed execution and trade migration adds native market facts", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0031_typed_execution_trade_facts.sql");

  assert.ok(migration);
  assert.match(migration.sql, /ALTER TABLE runtime\.executions/);
  assert.match(migration.sql, /ALTER TABLE runtime\.trades/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS execution_price_num NUMERIC/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS price_num NUMERIC/);
  assert.match(migration.sql, /CREATE TRIGGER executions_set_typed_facts/);
  assert.match(migration.sql, /CREATE TRIGGER trades_set_typed_facts/);
  assert.match(migration.sql, /idx_trades_instrument_occurred_typed/);
});

test("typed order migration adds native order facts", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0032_typed_order_facts.sql");

  assert.ok(migration);
  assert.match(migration.sql, /ALTER TABLE runtime\.orders/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS limit_price_num NUMERIC/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS accepted_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /CREATE TRIGGER orders_set_typed_facts/);
  assert.match(migration.sql, /idx_orders_participant_client_order_accepted_typed/);
  assert.match(migration.sql, /idx_orders_accepted_typed/);
});

test("typed canonical time migration adds native audit timestamps", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0033_typed_canonical_time_facts.sql");

  assert.ok(migration);
  assert.match(migration.sql, /ALTER TABLE runtime\.canonical_command_results/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS accepted_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS completed_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS emitted_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS created_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ/);
  assert.match(migration.sql, /CREATE TRIGGER canonical_command_outcomes_set_typed_facts/);
  assert.match(migration.sql, /idx_canonical_command_outcomes_occurred_typed/);
});

test("batch payload gin index is removed from hot canonical materialization", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "runtime/0035_drop_batch_payload_gin_lookup_index.sql");

  assert.ok(migration);
  assert.match(migration.sql, /DROP INDEX IF EXISTS runtime\.idx_canonical_venue_event_batches_payload_json_gin/);
});

test("command-log archive migration creates partitioned terminal result archive", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "command_log/0015_command_results_archive.sql");

  assert.ok(migration);
  assert.match(migration.sql, /CREATE TABLE IF NOT EXISTS command_log\.command_results_archive/);
  assert.match(migration.sql, /PARTITION BY RANGE \(completed_at\)/);
  assert.match(migration.sql, /PARTITION OF command_log\.command_results_archive DEFAULT/);
  assert.match(migration.sql, /PRIMARY KEY \(completed_at, command_id\)/);
});

test("runtime archive migrations create partitioned cold-history targets", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const canonicalMigration = migrations.find(
    (candidate) => candidate.id === "runtime/0036_canonical_archive_tables.sql",
  );
  const marketHistoryMigration = migrations.find(
    (candidate) => candidate.id === "runtime/0037_runtime_event_trade_archive_tables.sql",
  );

  assert.ok(canonicalMigration);
  assert.match(canonicalMigration.sql, /CREATE TABLE IF NOT EXISTS runtime\.canonical_venue_event_batches_archive/);
  assert.match(canonicalMigration.sql, /PARTITION BY RANGE \(materialized_at\)/);
  assert.match(canonicalMigration.sql, /PARTITION OF runtime\.canonical_command_outcomes_archive DEFAULT/);
  assert.match(canonicalMigration.sql, /PRIMARY KEY \(materialized_at, command_id\)/);

  assert.ok(marketHistoryMigration);
  assert.match(marketHistoryMigration.sql, /CREATE TABLE IF NOT EXISTS runtime\.trades_archive/);
  assert.match(marketHistoryMigration.sql, /PARTITION BY RANGE \(occurred_at_ts\)/);
  assert.match(marketHistoryMigration.sql, /PARTITION OF runtime\.runtime_events_archive DEFAULT/);
  assert.match(marketHistoryMigration.sql, /PRIMARY KEY \(occurred_at_ts, event_id\)/);
});

test("runtime projection lock-order migration hardens dirty queues", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find(
    (candidate) => candidate.id === "runtime/0038_projection_dirty_lock_order.sql",
  );

  assert.ok(migration);
  assert.match(migration.sql, /FOR UPDATE SKIP LOCKED/);
  assert.match(migration.sql, /ORDER BY instrument_id/);
  assert.match(migration.sql, /DELETE FROM runtime\.market_data_snapshot_dirty dirty\s+USING selected_dirty/);
});

test("runtime dirty queue migration moves rebuildable queues out of WAL", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find(
    (candidate) => candidate.id === "runtime/0042_unlogged_projection_dirty_queues.sql",
  );

  assert.ok(migration);
  assert.match(migration.sql, /ALTER TABLE runtime\.order_lifecycle_dirty SET UNLOGGED/);
  assert.match(migration.sql, /ALTER TABLE runtime\.market_data_snapshot_dirty SET UNLOGGED/);
});

test("runtime event payload migration moves event JSON off hot rows", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find(
    (candidate) => candidate.id === "runtime/0043_runtime_event_payload_cold_table.sql",
  );

  assert.ok(migration);
  assert.match(migration.sql, /CREATE TABLE IF NOT EXISTS runtime\.runtime_event_payloads/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS modify_quantity_units TEXT/);
  assert.match(migration.sql, /ADD COLUMN IF NOT EXISTS modify_limit_price TEXT/);
  assert.match(migration.sql, /INSERT INTO runtime\.runtime_event_payloads\(event_id, payload_json\)/);
  assert.match(migration.sql, /payload_json,\s+occurred_at,\s+modify_quantity_units,\s+modify_limit_price/s);
  assert.match(migration.sql, /'\{\}'::jsonb,\s+event->>'occurredAt'/s);
  assert.match(migration.sql, /filled_quantity_units::TEXT/);
  assert.doesNotMatch(migration.sql, /TRIM\(TRAILING '0' FROM filled_quantity_units::TEXT\)/);
});

test("idempotent lifecycle projection migration skips no-op row rewrites", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find(
    (candidate) => candidate.id === "runtime/0044_idempotent_lifecycle_projection.sql",
  );

  assert.ok(migration);
  assert.match(migration.sql, /INSERT INTO runtime\.order_lifecycle_state AS lifecycle/);
  assert.match(migration.sql, /WHERE lifecycle\.engine_order_id IS DISTINCT FROM EXCLUDED\.engine_order_id/);
  assert.match(migration.sql, /lifecycle\.limit_price_num IS DISTINCT FROM EXCLUDED\.limit_price_num/);
  assert.match(migration.sql, /SELECT COUNT\(\*\) INTO projected_count FROM cleared/);
});

test("runtime event index migration drops legacy all-event indexes", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find(
    (candidate) => candidate.id === "runtime/0045_drop_legacy_runtime_event_indexes.sql",
  );

  assert.ok(migration);
  assert.match(migration.sql, /DROP INDEX IF EXISTS runtime\.runtime_events_occurred_at_idx/);
  assert.match(migration.sql, /DROP INDEX IF EXISTS runtime\.runtime_events_trace_seq_idx/);
  assert.match(migration.sql, /DROP INDEX IF EXISTS runtime\.idx_runtime_events_occurred_event/);
  assert.doesNotMatch(migration.sql, /runtime_events_order_occurred_idx/);
});

test("command outcome projection preserves command correlation metadata", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find(
    (candidate) => candidate.id === "runtime/0039_command_outcome_projection_metadata.sql",
  );

  assert.ok(migration);
  assert.match(migration.sql, /COALESCE\(payloads\.payload_json, '\{\}'::jsonb\) AS command_payload/);
  assert.match(migration.sql, /'traceId', COALESCE\(NULLIF\(command_payload->>'traceId', ''\), command_id\)/);
  assert.match(migration.sql, /'causationId', COALESCE\(NULLIF\(command_payload->>'causationId', ''\), command_id\)/);
  assert.match(migration.sql, /'correlationId', COALESCE\(NULLIF\(command_payload->>'correlationId', ''\), command_id\)/);
});

test("submit outcome projection migration splits command-status and timeline stages", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find(
    (candidate) => candidate.id === "runtime/0040_split_submit_outcome_projection_stages.sql",
  );

  assert.ok(migration);
  assert.match(migration.sql, /runtime\.runtime_persist_submit_outcome_status_stage/);
  assert.match(migration.sql, /runtime\.runtime_persist_submit_outcome_timeline_stage/);
  assert.match(migration.sql, /p_projection_stage TEXT/);
  assert.match(migration.sql, /'command-status', 'status', 'lifecycle', 'core'/);
  assert.match(migration.sql, /RETURN runtime\.runtime_persist_submit_outcomes\(p_outcomes, 'full'\)/);
});

test("wraps migration SQL with checksum ledger insert", async () => {
  const migrations = await discoverMigrations(migrationsRoot);
  const migration = migrations.find((candidate) => candidate.id === "boundary/0002_live_boundary_tables.sql");

  assert.ok(migration);
  const sql = buildApplySql(migration);

  assert.match(sql, /^BEGIN;/);
  assert.match(sql, /INSERT INTO public\.reef_schema_migrations/);
  assert.match(sql, /boundary\/0002_live_boundary_tables\.sql/);
  assert.match(sql, /COMMIT;$/);
});

test("routes arena migrations only to arena database target", async () => {
  const migrations = await discoverMigrations(migrationsRoot);

  const operationalTargetMigrations = migrationsForTarget(
    { domains: ["runtime", "auth", "admin", "boundary", "command_log", "orchestration", "settlement", "analytics"] },
    migrations,
  );
  const arenaTargetMigrations = migrationsForTarget({ domains: ["arena"] }, migrations);

  assert.ok(operationalTargetMigrations.some((migration) => migration.id === "runtime/0001_runtime_init.sql"));
  assert.ok(operationalTargetMigrations.some((migration) => migration.id === "settlement/0001_p2_exception_facts.sql"));
  assert.ok(!operationalTargetMigrations.some((migration) => migration.id.startsWith("arena/")));
  assert.deepEqual(
    arenaTargetMigrations.map((migration) => migration.id),
    [
      "arena/0001_arena_registry.sql",
      "arena/0002_arena_run_bot_results.sql",
      "arena/0003_arena_run_bot_result_visibility.sql",
      "arena/0004_arena_run_enforcement_events.sql",
      "arena/0005_arena_bot_entitlements.sql",
      "arena/0006_remove_arena_bot_limits.sql",
    ],
  );
});
