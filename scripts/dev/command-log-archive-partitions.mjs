import { execFile } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { basename, dirname, join, resolve } from "node:path";
import { promisify } from "node:util";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

const execFileAsync = promisify(execFile);

export function parseArchivePartitionMonth(raw) {
  const value = String(raw ?? "").trim();
  const match = value.match(/^(\d{4})-(\d{2})$/);
  if (!match) {
    throw new Error(`invalid archive partition month: ${raw}. Use YYYY-MM.`);
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (month < 1 || month > 12) {
    throw new Error(`invalid archive partition month: ${raw}. Use YYYY-MM.`);
  }
  const nextYear = month === 12 ? year + 1 : year;
  const nextMonth = month === 12 ? 1 : month + 1;
  return {
    value,
    suffix: `${year}_${String(month).padStart(2, "0")}`,
    start: `${year}-${String(month).padStart(2, "0")}-01T00:00:00Z`,
    end: `${nextYear}-${String(nextMonth).padStart(2, "0")}-01T00:00:00Z`,
  };
}

export function buildListArchivePartitionsSql() {
  return `
SELECT
  child_namespace.nspname || '.' || child.relname AS partition_name,
  pg_get_expr(child.relpartbound, child.oid) AS partition_bound,
  COALESCE(stats.n_live_tup, 0) AS estimated_live_rows
FROM pg_inherits
JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
JOIN pg_namespace parent_namespace ON parent_namespace.oid = parent.relnamespace
JOIN pg_class child ON child.oid = pg_inherits.inhrelid
JOIN pg_namespace child_namespace ON child_namespace.oid = child.relnamespace
LEFT JOIN pg_stat_user_tables stats ON stats.relid = child.oid
WHERE parent_namespace.nspname = 'command_log'
  AND parent.relname = 'command_results_archive'
ORDER BY partition_name;
`.trim();
}

export function buildCreateArchivePartitionSql({ month }) {
  const parsed = parseArchivePartitionMonth(month);
  return `
CREATE TABLE IF NOT EXISTS command_log.command_results_archive_${parsed.suffix}
  PARTITION OF command_log.command_results_archive
  FOR VALUES FROM ('${parsed.start}'::timestamptz) TO ('${parsed.end}'::timestamptz);
`.trim();
}

export function buildDropArchivePartitionSql({ month }) {
  const parsed = parseArchivePartitionMonth(month);
  const partition = `command_log.command_results_archive_${parsed.suffix}`;
  return `
ALTER TABLE command_log.command_results_archive
  DETACH PARTITION ${partition};
DROP TABLE ${partition};
`.trim();
}

export function buildExportArchivePartitionSql({ month }) {
  const parsed = parseArchivePartitionMonth(month);
  return `
COPY (
  SELECT row_to_json(archive_row)::TEXT
  FROM command_log.command_results_archive_${parsed.suffix} archive_row
  ORDER BY completed_at, command_id
) TO STDOUT;
`.trim();
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error?.message ?? error);
    process.exit(1);
  });
}

async function main() {
  loadDotEnv();

  const action = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_ACTION", "list");
  const apply = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_APPLY", "0") === "1";
  const month = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_MONTH", "");
  const artifactDir = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_ARTIFACT_DIR", "/tmp");
  const reportOut = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_REPORT_OUT", "/tmp/reef-command-log-archive-partitions.json");
  const exportOut = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_EXPORT_OUT", "/tmp/reef-command-results-archive.jsonl");
  const service = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_DB_SERVICE", "postgres");
  const dbUser = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_DB_USER", "reef");
  const dbName = env("DEV_COMMAND_LOG_ARCHIVE_PARTITION_DB_NAME", "reef");

  mkdirSync(artifactDir, { recursive: true });

  const startedAt = new Date().toISOString();
  let rows = [];
  let plannedSql = "";
  let exportedRows = 0;
  let resolvedExportOut = "";

  if (action === "list") {
    rows = await execPsql({ service, dbUser, dbName, sql: buildListArchivePartitionsSql() });
  } else if (action === "create") {
    requireMonth(month);
    plannedSql = buildCreateArchivePartitionSql({ month });
    if (apply) {
      await execPsql({ service, dbUser, dbName, sql: plannedSql, capture: false });
    }
  } else if (action === "drop") {
    requireMonth(month);
    plannedSql = buildDropArchivePartitionSql({ month });
    if (apply) {
      await execPsql({ service, dbUser, dbName, sql: plannedSql, capture: false });
    }
  } else if (action === "export") {
    requireMonth(month);
    plannedSql = buildExportArchivePartitionSql({ month });
    if (apply) {
      const exportLines = await execPsqlRaw({ service, dbUser, dbName, sql: plannedSql });
      resolvedExportOut = join(artifactDir, basename(exportOut));
      mkdirSync(dirname(resolvedExportOut), { recursive: true });
      writeFileSync(resolvedExportOut, exportLines);
      exportedRows = exportLines.trim() === "" ? 0 : exportLines.trimEnd().split(/\r?\n/).length;
    }
  } else {
    throw new Error(`invalid DEV_COMMAND_LOG_ARCHIVE_PARTITION_ACTION: ${action}`);
  }

  const report = {
    startedAt,
    finishedAt: new Date().toISOString(),
    action,
    mode: apply ? "apply" : "dry-run",
    month,
    plannedSql,
    partitions: rows.map(toPartition),
    exportOut: resolvedExportOut,
    exportedRows,
  };
  const resolvedReportOut = join(artifactDir, basename(reportOut || "reef-command-log-archive-partitions.json"));
  writeFileSync(resolvedReportOut, JSON.stringify(report, null, 2));
  printSummary(report, resolvedReportOut);
}

function printSummary(report, reportOut) {
  console.log("Command Log Archive Partitions");
  console.log(`Action : ${report.action}`);
  console.log(`Mode   : ${report.mode}`);
  if (report.month) console.log(`Month  : ${report.month}`);
  if (report.partitions.length > 0) {
    for (const partition of report.partitions) {
      console.log(`  ${partition.partitionName} ${partition.partitionBound} rows=${partition.estimatedLiveRows}`);
    }
  }
  if (report.exportOut) {
    console.log(`Export : ${report.exportOut} (${report.exportedRows} rows)`);
  }
  console.log(`Report : ${reportOut}`);
  if (report.mode === "dry-run" && report.action !== "list") {
    console.log("Set DEV_COMMAND_LOG_ARCHIVE_PARTITION_APPLY=1 to execute the planned SQL.");
  }
}

async function execPsql({ service, dbUser, dbName, sql, capture = true }) {
  const stdout = await execPsqlRaw({ service, dbUser, dbName, sql, capture });
  if (!capture) return [];
  return stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split("\t"));
}

async function execPsqlRaw({ service, dbUser, dbName, sql, capture = true }) {
  const { stdout } = await execFileAsync(
    "docker",
    [
      "compose",
      "-f",
      "docker-compose.yml",
      "exec",
      "-T",
      service,
      "psql",
      "-U",
      dbUser,
      "-d",
      dbName,
      "-v",
      "ON_ERROR_STOP=1",
      "-X",
      "-q",
      "-t",
      "-A",
      "-F",
      "\t",
      "-c",
      sql,
    ],
    {
      cwd: process.cwd(),
      stdio: capture ? "pipe" : "inherit",
      maxBuffer: 50 * 1024 * 1024,
    },
  );
  return capture ? stdout : "";
}

function toPartition(row) {
  return {
    partitionName: row[0] ?? "",
    partitionBound: row[1] ?? "",
    estimatedLiveRows: Number(row[2] ?? 0),
  };
}

function requireMonth(month) {
  parseArchivePartitionMonth(month);
}
