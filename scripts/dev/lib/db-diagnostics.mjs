import { execFile } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { promisify } from "node:util";
import { composeArgs } from "./compose-utils.mjs";

const execFileAsync = promisify(execFile);

export const defaultDiagnosticSchemas = ["runtime", "boundary", "command_log"];

export async function captureDbDiagnosticsSnapshot({
  diagnosticsDir,
  stage,
  service = "postgres",
  dbUser = "reef",
  dbName = "reef",
  schemas = defaultDiagnosticSchemas,
}) {
  mkdirSync(diagnosticsDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const safeSchemas = schemas.map(normalizeSchemaName);
  const info = { capturedAt, stage, service, dbUser, dbName, schemas: safeSchemas };
  try {
    const [
      serverVersionRows,
      bgwriterRows,
      walRows,
      databaseRows,
      activityWaitRows,
      walSettingsRows,
      hasCheckpointerRows,
      hasIoRows,
      tables,
    ] = await Promise.all([
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "show server_version_num;",
        columns: ["serverVersionNum"],
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "select * from pg_stat_bgwriter;",
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "select wal_records, wal_fpi, wal_bytes, wal_buffers_full, wal_write, wal_sync, wal_write_time, wal_sync_time from pg_stat_wal;",
        columns: [
          "walRecords",
          "walFpi",
          "walBytes",
          "walBuffersFull",
          "walWrite",
          "walSync",
          "walWriteTime",
          "walSyncTime",
        ],
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "select xact_commit, xact_rollback, blks_read, blks_hit, tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted, conflicts, temp_files, temp_bytes, deadlocks from pg_stat_database where datname = current_database();",
        columns: [
          "xactCommit",
          "xactRollback",
          "blocksRead",
          "blocksHit",
          "tuplesReturned",
          "tuplesFetched",
          "tuplesInserted",
          "tuplesUpdated",
          "tuplesDeleted",
          "conflicts",
          "tempFiles",
          "tempBytes",
          "deadlocks",
        ],
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "select coalesce(wait_event_type, 'running') as wait_event_type, coalesce(wait_event, '') as wait_event, coalesce(state, '') as state, count(*) as count from pg_stat_activity where datname = current_database() group by 1, 2, 3 order by count desc, wait_event_type, wait_event, state;",
        columns: ["waitEventType", "waitEvent", "state", "count"],
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "select name, setting, coalesce(unit, '') as unit from pg_settings where name in ('checkpoint_completion_target', 'checkpoint_flush_after', 'checkpoint_timeout', 'fsync', 'full_page_writes', 'max_wal_size', 'min_wal_size', 'synchronous_commit', 'wal_buffers', 'wal_compression', 'wal_sync_method') order by name;",
        columns: ["name", "setting", "unit"],
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "select coalesce((select count(1) from pg_catalog.pg_views where schemaname='pg_catalog' and viewname='pg_stat_checkpointer'),0) as count;",
        columns: ["count"],
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: "select coalesce((select count(1) from pg_catalog.pg_views where schemaname='pg_catalog' and viewname='pg_stat_io'),0) as count;",
        columns: ["count"],
      }),
      queryDbRows({
        service,
        dbUser,
        dbName,
        sql: tableStatsSql(safeSchemas),
        columns: [
          "schemaName",
          "tableName",
          "totalBytes",
          "tableBytes",
          "indexBytes",
          "toastBytes",
          "liveTuples",
          "deadTuples",
          "seqScans",
          "idxScans",
          "inserts",
          "updates",
          "deletes",
          "hotUpdates",
          "vacuumCount",
          "autovacuumCount",
          "analyzeCount",
          "autoanalyzeCount",
        ],
      }),
    ]);

    const hasCheckpointer = Number(hasCheckpointerRows[0]?.count ?? 0) > 0;
    const hasIo = Number(hasIoRows[0]?.count ?? 0) > 0;
    const checkpointerRows = hasCheckpointer
      ? await queryDbRows({
          service,
          dbUser,
          dbName,
          sql: "select * from pg_stat_checkpointer;",
        })
      : [];
    const ioRows = hasIo
      ? await queryDbRows({
          service,
          dbUser,
          dbName,
          sql: "select backend_type, object, context, reads, read_time, writes, write_time, writebacks, writeback_time, extends, extend_time, op_bytes, hits, evictions, reuses, fsyncs, fsync_time from pg_stat_io where object in ('wal', 'relation') order by object, backend_type, context;",
          columns: [
            "backendType",
            "object",
            "context",
            "reads",
            "readTime",
            "writes",
            "writeTime",
            "writebacks",
            "writebackTime",
            "extends",
            "extendTime",
            "opBytes",
            "hits",
            "evictions",
            "reuses",
            "fsyncs",
            "fsyncTime",
          ],
        })
      : [];
    const snapshot = {
      ...info,
      serverVersionNum: serverVersionRows[0]?.serverVersionNum ?? "",
      tablesBySchema: groupTablesBySchema(tables),
      bgwriter: bgwriterRows[0] ?? {},
      wal: coerceNumericStats(walRows[0] ?? {}),
      database: coerceNumericStats(databaseRows[0] ?? {}),
      checkpointer: checkpointerRows[0] ?? {},
      activityWaits: activityWaitRows.map(coerceNumericStats),
      walSettings: Object.fromEntries(
        walSettingsRows.map((row) => [
          row.name,
          {
            setting: coerceNumber(row.setting),
            unit: row.unit ?? "",
          },
        ]),
      ),
      io: ioRows.map(coerceNumericStats),
      walIo: summarizeIoRows(ioRows, "wal"),
    };

    writeFileSync(join(diagnosticsDir, `${stage}-db-diagnostics.json`), JSON.stringify(snapshot, null, 2));
    writeFileSync(join(diagnosticsDir, `${stage}-table-stats.csv`), toCsv(tables) + "\n");
    writeFileSync(join(diagnosticsDir, `${stage}-pg_stat_bgwriter.csv`), toCsv(bgwriterRows) + "\n");
    writeFileSync(join(diagnosticsDir, `${stage}-pg_stat_wal.csv`), toCsv(walRows) + "\n");
    writeFileSync(join(diagnosticsDir, `${stage}-pg_stat_database.csv`), toCsv(databaseRows) + "\n");
    writeFileSync(join(diagnosticsDir, `${stage}-pg_stat_activity_waits.csv`), toCsv(activityWaitRows) + "\n");
    writeFileSync(join(diagnosticsDir, `${stage}-pg_settings_wal.csv`), toCsv(walSettingsRows) + "\n");
    if (hasCheckpointer) {
      writeFileSync(join(diagnosticsDir, `${stage}-pg_stat_checkpointer.csv`), toCsv(checkpointerRows) + "\n");
    }
    if (hasIo) {
      writeFileSync(join(diagnosticsDir, `${stage}-pg_stat_io.csv`), toCsv(ioRows) + "\n");
    }
    return { ok: true, snapshot };
  } catch (error) {
    const failure = { ...info, ok: false, error: String(error?.message || error) };
    writeFileSync(join(diagnosticsDir, `${stage}-capture-error.json`), JSON.stringify(failure, null, 2));
    return failure;
  }
}

export async function captureDbDiagnosticsLogs({ diagnosticsDir, service = "postgres", since = "30m" }) {
  try {
    const { stdout, stderr } = await execFileAsync(
      "docker",
      composeArgs(["logs", "--since", since, service]),
      { cwd: process.cwd(), maxBuffer: 50 * 1024 * 1024 },
    );
    writeFileSync(join(diagnosticsDir, "postgres-logs.txt"), `${stdout}${stderr}`);
    return { ok: true };
  } catch (error) {
    const failure = { ok: false, error: String(error?.message || error), capturedAt: new Date().toISOString() };
    writeFileSync(join(diagnosticsDir, "postgres-logs-error.json"), JSON.stringify(failure, null, 2));
    return failure;
  }
}

export function summarizeDiagnosticsDelta(preResult, postResult) {
  if (!preResult?.ok || !postResult?.ok) {
    return { ok: false, reason: "missing pre or post diagnostics snapshot" };
  }
  const preTables = flattenTables(preResult.snapshot.tablesBySchema);
  const postTables = flattenTables(postResult.snapshot.tablesBySchema);
  const deltas = [];
  for (const [key, post] of postTables) {
    const pre = preTables.get(key) ?? {};
    deltas.push({
      table: key,
      totalBytesDelta: numberValue(post.totalBytes) - numberValue(pre.totalBytes),
      tableBytesDelta: numberValue(post.tableBytes) - numberValue(pre.tableBytes),
      indexBytesDelta: numberValue(post.indexBytes) - numberValue(pre.indexBytes),
      liveTuplesDelta: numberValue(post.liveTuples) - numberValue(pre.liveTuples),
      deadTuplesDelta: numberValue(post.deadTuples) - numberValue(pre.deadTuples),
      insertsDelta: numberValue(post.inserts) - numberValue(pre.inserts),
      updatesDelta: numberValue(post.updates) - numberValue(pre.updates),
      deletesDelta: numberValue(post.deletes) - numberValue(pre.deletes),
      hotUpdatesDelta: numberValue(post.hotUpdates) - numberValue(pre.hotUpdates),
    });
  }
  deltas.sort((a, b) => Math.abs(b.totalBytesDelta) - Math.abs(a.totalBytesDelta));
  return {
    ok: true,
    tables: deltas,
    wal: numericDelta(preResult.snapshot.wal, postResult.snapshot.wal),
    walIo: numericDelta(preResult.snapshot.walIo, postResult.snapshot.walIo),
    database: numericDelta(preResult.snapshot.database, postResult.snapshot.database),
    activityWaitsAfter: postResult.snapshot.activityWaits ?? [],
  };
}

function tableStatsSql(schemas) {
  const schemaList = schemas.map(sqlString).join(", ");
  return `
select
  n.nspname as schema_name,
  c.relname as table_name,
  pg_total_relation_size(c.oid) as total_bytes,
  pg_relation_size(c.oid) as table_bytes,
  pg_indexes_size(c.oid) as index_bytes,
  greatest(pg_total_relation_size(c.oid) - pg_relation_size(c.oid) - pg_indexes_size(c.oid), 0) as toast_bytes,
  coalesce(s.n_live_tup, 0) as live_tuples,
  coalesce(s.n_dead_tup, 0) as dead_tuples,
  coalesce(s.seq_scan, 0) as seq_scans,
  coalesce(s.idx_scan, 0) as idx_scans,
  coalesce(s.n_tup_ins, 0) as inserts,
  coalesce(s.n_tup_upd, 0) as updates,
  coalesce(s.n_tup_del, 0) as deletes,
  coalesce(s.n_tup_hot_upd, 0) as hot_updates,
  coalesce(s.vacuum_count, 0) as vacuum_count,
  coalesce(s.autovacuum_count, 0) as autovacuum_count,
  coalesce(s.analyze_count, 0) as analyze_count,
  coalesce(s.autoanalyze_count, 0) as autoanalyze_count
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
left join pg_stat_user_tables s on s.relid = c.oid
where n.nspname in (${schemaList})
  and c.relkind in ('r', 'p')
order by pg_total_relation_size(c.oid) desc, n.nspname, c.relname;`;
}

async function queryDbRows({ service, dbUser, dbName, sql, columns }) {
  const { stdout } = await execFileAsync(
    "docker",
    composeArgs([
      "exec",
      "-T",
      service,
      "psql",
      "-U",
      dbUser,
      "-d",
      dbName,
      "-X",
      "-q",
      "-t",
      "-A",
      "-F",
      "\t",
      "-c",
      sql,
    ]),
    { cwd: process.cwd(), maxBuffer: 10 * 1024 * 1024 },
  );
  const rows = stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split("\t"));
  if (columns) {
    return rows.map((row) => Object.fromEntries(columns.map((column, index) => [column, row[index] ?? ""])));
  }
  if (rows.length !== 1) {
    return rows.map((row, index) => ({ row: String(index), values: row }));
  }
  return rows.map((row) => Object.fromEntries(row.map((value, index) => [`column${index + 1}`, value])));
}

function groupTablesBySchema(tables) {
  const grouped = {};
  for (const table of tables) {
    const { schemaName, tableName, ...stats } = table;
    if (!grouped[schemaName]) grouped[schemaName] = {};
    grouped[schemaName][tableName] = coerceNumericStats(stats);
  }
  return grouped;
}

function coerceNumericStats(stats) {
  return Object.fromEntries(
    Object.entries(stats).map(([key, value]) => [key, value === "" ? value : coerceNumber(value)]),
  );
}

function coerceNumber(value) {
  const raw = String(value ?? "");
  return /^-?\d+(?:\.\d+)?$/.test(raw) ? Number(raw) : value;
}

function summarizeIoRows(rows, objectName) {
  const totals = {};
  for (const row of rows) {
    if (row.object !== objectName) continue;
    const stats = coerceNumericStats(row);
    for (const [key, value] of Object.entries(stats)) {
      if (typeof value === "number") {
        totals[key] = (totals[key] ?? 0) + value;
      }
    }
  }
  return totals;
}

function flattenTables(tablesBySchema) {
  const flattened = new Map();
  for (const [schema, tables] of Object.entries(tablesBySchema ?? {})) {
    for (const [table, stats] of Object.entries(tables ?? {})) {
      flattened.set(`${schema}.${table}`, stats);
    }
  }
  return flattened;
}

function numericDelta(pre, post) {
  const keys = new Set([...Object.keys(pre ?? {}), ...Object.keys(post ?? {})]);
  return Object.fromEntries(
    [...keys].map((key) => [key, numberValue(post?.[key]) - numberValue(pre?.[key])]),
  );
}

function toCsv(rows) {
  if (!rows.length) return "";
  const columns = Object.keys(rows[0]);
  const lines = [columns.join(",")];
  for (const row of rows) {
    lines.push(columns.map((column) => csvValue(row[column])).join(","));
  }
  return lines.join("\n");
}

function csvValue(value) {
  const raw = String(value ?? "");
  if (!/[",\n\r]/.test(raw)) return raw;
  return `"${raw.replaceAll('"', '""')}"`;
}

function normalizeSchemaName(raw) {
  const value = String(raw ?? "").trim();
  if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(value)) {
    throw new Error(`invalid schema identifier: ${value}`);
  }
  return value;
}

function numberValue(value) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}
