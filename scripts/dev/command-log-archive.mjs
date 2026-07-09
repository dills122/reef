import { execFile } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { basename, join, resolve } from "node:path";
import { promisify } from "node:util";
import {
  captureDbDiagnosticsSnapshot,
  defaultDiagnosticSchemas,
  summarizeDiagnosticsDelta,
} from "./lib/db-diagnostics.mjs";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";
import {
  buildRetentionPinExclusionPredicate,
  parseDurationSeconds,
} from "./command-log-prune.mjs";

const execFileAsync = promisify(execFile);

export function buildArchiveEligibleCountSql({ olderThanSeconds }) {
  return `
SELECT COUNT(*) AS eligible_count
FROM command_log.command_results results
JOIN command_log.commands commands ON commands.command_id = results.command_id
WHERE results.completed_at < NOW() - (${olderThanSeconds}::double precision * INTERVAL '1 second')
  AND NOT EXISTS (
    SELECT 1
    FROM command_log.command_work_queue queue
    WHERE queue.command_id = results.command_id
  )
  AND ${buildRetentionPinExclusionPredicate("commands")};
`.trim();
}

export function buildArchiveBatchSql({ olderThanSeconds, batchSize }) {
  return `
WITH candidates AS (
  SELECT
    results.command_id,
    results.status,
    results.attempt_count,
    results.last_error,
    results.response_status,
    results.response_payload_json,
    results.completed_at
  FROM command_log.command_results results
  JOIN command_log.commands commands ON commands.command_id = results.command_id
  WHERE results.completed_at < NOW() - (${olderThanSeconds}::double precision * INTERVAL '1 second')
    AND NOT EXISTS (
      SELECT 1
      FROM command_log.command_work_queue queue
      WHERE queue.command_id = results.command_id
    )
    AND ${buildRetentionPinExclusionPredicate("commands")}
  ORDER BY results.completed_at, results.command_id
  LIMIT ${batchSize}
  FOR UPDATE OF results SKIP LOCKED
),
archived AS (
  INSERT INTO command_log.command_results_archive(
    command_id,
    status,
    attempt_count,
    last_error,
    response_status,
    response_payload_json,
    completed_at,
    archived_at
  )
  SELECT
    command_id,
    status,
    attempt_count,
    last_error,
    response_status,
    response_payload_json,
    completed_at,
    NOW()
  FROM candidates
  ON CONFLICT (completed_at, command_id) DO UPDATE SET
    status = EXCLUDED.status,
    attempt_count = EXCLUDED.attempt_count,
    last_error = EXCLUDED.last_error,
    response_status = EXCLUDED.response_status,
    response_payload_json = EXCLUDED.response_payload_json,
    archived_at = EXCLUDED.archived_at
  RETURNING command_id, completed_at
),
deleted_live AS (
  DELETE FROM command_log.command_results results
  USING archived
  WHERE results.command_id = archived.command_id
    AND results.completed_at = archived.completed_at
  RETURNING results.command_id
)
SELECT
  (SELECT COUNT(*) FROM candidates) AS candidate_count,
  (SELECT COUNT(*) FROM archived) AS archived_count,
  (SELECT COUNT(*) FROM deleted_live) AS deleted_live_count;
`.trim();
}

export function buildArchiveVacuumSql() {
  return [
    { table: "command_log.command_results", sql: "VACUUM (ANALYZE, PARALLEL 0) command_log.command_results;" },
    {
      table: "command_log.command_results_archive",
      sql: "VACUUM (ANALYZE, PARALLEL 0) command_log.command_results_archive;",
    },
  ];
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error?.message ?? error);
    process.exit(1);
  });
}

async function main() {
  loadDotEnv();

  const apply = env("DEV_COMMAND_LOG_ARCHIVE_APPLY", "0") === "1";
  const olderThan = env("DEV_COMMAND_LOG_ARCHIVE_OLDER_THAN", "24h");
  const olderThanSeconds = parseDurationSeconds(olderThan);
  const batchSize = parsePositiveInt(env("DEV_COMMAND_LOG_ARCHIVE_BATCH_SIZE", "50000"), "DEV_COMMAND_LOG_ARCHIVE_BATCH_SIZE");
  const maxBatches = parsePositiveInt(env("DEV_COMMAND_LOG_ARCHIVE_MAX_BATCHES", "100"), "DEV_COMMAND_LOG_ARCHIVE_MAX_BATCHES");
  const runVacuum = env("DEV_COMMAND_LOG_ARCHIVE_VACUUM", "1") === "1";
  const captureDiagnostics = env("DEV_COMMAND_LOG_ARCHIVE_CAPTURE_DB_DIAGNOSTICS", "1") === "1";
  const artifactDir = env("DEV_COMMAND_LOG_ARCHIVE_ARTIFACT_DIR", "/tmp");
  const reportOut = env("DEV_COMMAND_LOG_ARCHIVE_REPORT_OUT", "/tmp/reef-command-log-archive.json");
  const service = env("DEV_COMMAND_LOG_ARCHIVE_DB_SERVICE", "postgres");
  const dbUser = env("DEV_COMMAND_LOG_ARCHIVE_DB_USER", "reef");
  const dbName = env("DEV_COMMAND_LOG_ARCHIVE_DB_NAME", "reef");
  const schemas = parseCsv(env("DEV_COMMAND_LOG_ARCHIVE_DB_SCHEMAS", defaultDiagnosticSchemas.join(",")));
  const reportBaseName = basename(reportOut.replace(/\.json$/, ""));
  const diagnosticsDir = join(artifactDir, `${reportBaseName}-db-diagnostics`);

  mkdirSync(artifactDir, { recursive: true });

  const startedAt = new Date().toISOString();
  let preDiagnostics = null;
  let postDiagnostics = null;
  if (captureDiagnostics) {
    preDiagnostics = await captureDbDiagnosticsSnapshot({
      diagnosticsDir,
      stage: "pre",
      service,
      dbUser,
      dbName,
      schemas,
    });
  }

  const eligibleBefore = await querySingleNumber({
    service,
    dbUser,
    dbName,
    sql: buildArchiveEligibleCountSql({ olderThanSeconds }),
  });

  let archived = 0;
  let deletedLive = 0;
  let batches = 0;
  const batchResults = [];
  const vacuum = [];
  if (apply) {
    while (batches < maxBatches) {
      const archivedThisBatch = await queryArchiveBatch({
        service,
        dbUser,
        dbName,
        sql: buildArchiveBatchSql({ olderThanSeconds, batchSize }),
      });
      if (archivedThisBatch.archivedCount > 0) batches += 1;
      archived += archivedThisBatch.archivedCount;
      deletedLive += archivedThisBatch.deletedLiveCount;
      batchResults.push(archivedThisBatch);
      if (archivedThisBatch.archivedCount === 0) break;
      if (archivedThisBatch.archivedCount < batchSize) break;
    }

    if (runVacuum && deletedLive > 0) {
      for (const vacuumCommand of buildArchiveVacuumSql()) {
        try {
          await execPsql({ service, dbUser, dbName, sql: vacuumCommand.sql, capture: false });
          vacuum.push({ table: vacuumCommand.table, ok: true });
        } catch (error) {
          vacuum.push({
            table: vacuumCommand.table,
            ok: false,
            error: String(error?.message || error),
          });
        }
      }
    }
  }

  const eligibleAfter = await querySingleNumber({
    service,
    dbUser,
    dbName,
    sql: buildArchiveEligibleCountSql({ olderThanSeconds }),
  });

  if (captureDiagnostics) {
    postDiagnostics = await captureDbDiagnosticsSnapshot({
      diagnosticsDir,
      stage: "post",
      service,
      dbUser,
      dbName,
      schemas,
    });
  }

  const report = {
    startedAt,
    finishedAt: new Date().toISOString(),
    mode: apply ? "apply" : "dry-run",
    olderThan,
    olderThanSeconds,
    batchSize,
    maxBatches,
    runVacuum,
    eligibleBefore,
    eligibleAfter,
    archived,
    deletedLive,
    batches,
    batchResults,
    vacuum,
    dbDiagnostics: captureDiagnostics
      ? {
          diagnosticsDir,
          schemas,
          pre: preDiagnostics,
          post: postDiagnostics,
          delta: summarizeDiagnosticsDelta(preDiagnostics, postDiagnostics),
        }
      : null,
  };

  writeFileSync(reportOut, JSON.stringify(report, null, 2));
  printSummary(report, reportOut);
}

function printSummary(report, reportOut) {
  console.log("Command Log Archive Summary");
  console.log(`Mode       : ${report.mode}`);
  console.log(`Older than : ${report.olderThan}`);
  console.log(`Eligible   : ${report.eligibleBefore}`);
  console.log(`Archived   : ${report.archived}`);
  console.log(`Deleted live results: ${report.deletedLive}`);
  console.log(`Remaining  : ${report.eligibleAfter}`);
  console.log(`Report     : ${reportOut}`);
  if (report.dbDiagnostics?.diagnosticsDir) {
    console.log(`Diagnostics: ${report.dbDiagnostics.diagnosticsDir}`);
  }
  if (report.mode === "dry-run") {
    console.log("Set DEV_COMMAND_LOG_ARCHIVE_APPLY=1 to archive eligible terminal command results.");
  }
}

async function querySingleNumber({ service, dbUser, dbName, sql }) {
  const rows = await execPsql({ service, dbUser, dbName, sql });
  return Number(rows[0]?.[0] ?? 0);
}

async function queryArchiveBatch({ service, dbUser, dbName, sql }) {
  const rows = await execPsql({ service, dbUser, dbName, sql });
  const row = rows[0] ?? [];
  return {
    candidateCount: Number(row[0] ?? 0),
    archivedCount: Number(row[1] ?? 0),
    deletedLiveCount: Number(row[2] ?? 0),
  };
}

async function execPsql({ service, dbUser, dbName, sql, capture = true }) {
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
      maxBuffer: 10 * 1024 * 1024,
    },
  );
  if (!capture) return [];
  return stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split("\t"));
}

function parseCsv(raw) {
  return String(raw ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}

function parsePositiveInt(raw, name) {
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}
