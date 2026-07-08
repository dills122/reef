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
    ],
  );
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
  assert.ok(migrations.some((migration) => migration.id === "settlement/0001_p2_exception_facts.sql"));
  assert.ok(migrations.some((migration) => migration.id === "arena/0001_arena_registry.sql"));
  assert.ok(migrations.some((migration) => migration.id === "analytics/0001_simulation_run_exports.sql"));
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
    ],
  );
});
