import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { buildApplySql, discoverMigrations, validateMigrationOrder } from "./migrate.mjs";

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
    ],
  );
  assert.ok(migrations.some((migration) => migration.id === "auth/0002_live_auth_tables.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0002_live_boundary_tables.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0003_command_capture_live_shape.sql"));
  assert.ok(migrations.some((migration) => migration.id === "boundary/0004_command_capture_legacy_defaults.sql"));
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
