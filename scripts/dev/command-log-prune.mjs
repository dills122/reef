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

const execFileAsync = promisify(execFile);

export function parseDurationSeconds(raw) {
  const value = String(raw ?? "").trim().toLowerCase();
  const match = value.match(/^(\d+(?:\.\d+)?)(ms|s|m|h|d)$/);
  if (!match) {
    throw new Error(`invalid duration: ${raw}. Use forms like 0s, 30m, 24h, or 7d.`);
  }
  const amount = Number(match[1]);
  const unit = match[2];
  const multiplier = {
    ms: 0.001,
    s: 1,
    m: 60,
    h: 60 * 60,
    d: 24 * 60 * 60,
  }[unit];
  return amount * multiplier;
}

export function buildEligibleCountSql({ olderThanSeconds }) {
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

export function buildDeleteBatchSql({ olderThanSeconds, batchSize }) {
  return `
WITH eligible AS (
  SELECT results.command_id
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
),
deleted AS (
  DELETE FROM command_log.commands commands
  USING eligible
  WHERE commands.command_id = eligible.command_id
  RETURNING commands.command_id
)
SELECT COUNT(*) AS deleted_count
FROM deleted;
`.trim();
}

export function buildRetentionPinExclusionPredicate(commandAlias) {
  return `NOT EXISTS (
    SELECT 1
    FROM command_log.retention_pins pins
    WHERE (pins.selector_type = 'command_id' AND pins.selector_value = ${commandAlias}.command_id)
       OR (pins.selector_type = 'idempotency_prefix' AND ${commandAlias}.idempotency_key LIKE pins.selector_value || '%')
       OR (pins.selector_type = 'trace_id' AND pins.selector_value = ${commandAlias}.trace_id)
       OR (pins.selector_type = 'correlation_id' AND pins.selector_value = ${commandAlias}.correlation_id)
       OR (pins.selector_type = 'client_id' AND pins.selector_value = ${commandAlias}.client_id)
       OR (pins.selector_type = 'run_id' AND pins.selector_value = ${commandAlias}.run_id)
  )`;
}

export function buildQueueCountsSql() {
  return `
SELECT status, COUNT(*) AS count
FROM command_log.command_work_queue
GROUP BY status
ORDER BY status;
`.trim();
}

export function buildVacuumSql() {
  return [
    { table: "command_log.commands", sql: "VACUUM (ANALYZE, PARALLEL 0) command_log.commands;" },
    { table: "command_log.command_results", sql: "VACUUM (ANALYZE, PARALLEL 0) command_log.command_results;" },
    { table: "command_log.command_work_queue", sql: "VACUUM (ANALYZE, PARALLEL 0) command_log.command_work_queue;" },
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

  const apply = env("DEV_COMMAND_LOG_PRUNE_APPLY", "0") === "1";
  const olderThan = env("DEV_COMMAND_LOG_PRUNE_OLDER_THAN", "24h");
  const olderThanSeconds = parseDurationSeconds(olderThan);
  const batchSize = parsePositiveInt(env("DEV_COMMAND_LOG_PRUNE_BATCH_SIZE", "50000"), "DEV_COMMAND_LOG_PRUNE_BATCH_SIZE");
  const maxBatches = parsePositiveInt(env("DEV_COMMAND_LOG_PRUNE_MAX_BATCHES", "100"), "DEV_COMMAND_LOG_PRUNE_MAX_BATCHES");
  const runVacuum = env("DEV_COMMAND_LOG_PRUNE_VACUUM", "1") === "1";
  const captureDiagnostics = env("DEV_COMMAND_LOG_PRUNE_CAPTURE_DB_DIAGNOSTICS", "1") === "1";
  const artifactDir = env("DEV_COMMAND_LOG_PRUNE_ARTIFACT_DIR", "/tmp");
  const reportOut = env("DEV_COMMAND_LOG_PRUNE_REPORT_OUT", "/tmp/reef-command-log-prune.json");
  const service = env("DEV_COMMAND_LOG_PRUNE_DB_SERVICE", "postgres");
  const dbUser = env("DEV_COMMAND_LOG_PRUNE_DB_USER", "reef");
  const dbName = env("DEV_COMMAND_LOG_PRUNE_DB_NAME", "reef");
  const schemas = parseCsv(env("DEV_COMMAND_LOG_PRUNE_DB_SCHEMAS", defaultDiagnosticSchemas.join(",")));
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
    sql: buildEligibleCountSql({ olderThanSeconds }),
  });
  const queueCountsBefore = await queryQueueCounts({ service, dbUser, dbName });

  let deleted = 0;
  let batches = 0;
  const vacuum = [];
  if (apply) {
    while (batches < maxBatches) {
      const deletedThisBatch = await querySingleNumber({
        service,
        dbUser,
        dbName,
        sql: buildDeleteBatchSql({ olderThanSeconds, batchSize }),
      });
      if (deletedThisBatch > 0) batches += 1;
      deleted += deletedThisBatch;
      if (deletedThisBatch === 0) break;
      if (deletedThisBatch < batchSize) break;
    }

    if (runVacuum && deleted > 0) {
      for (const vacuumCommand of buildVacuumSql()) {
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
    sql: buildEligibleCountSql({ olderThanSeconds }),
  });
  const queueCountsAfter = await queryQueueCounts({ service, dbUser, dbName });

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
    deleted,
    batches,
    vacuum,
    queueCountsBefore,
    queueCountsAfter,
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
  console.log("Command Log Prune Summary");
  console.log(`Mode       : ${report.mode}`);
  console.log(`Older than : ${report.olderThan}`);
  console.log(`Eligible   : ${report.eligibleBefore}`);
  console.log(`Deleted    : ${report.deleted}`);
  console.log(`Remaining  : ${report.eligibleAfter}`);
  console.log(`Active queue before: ${JSON.stringify(report.queueCountsBefore)}`);
  console.log(`Active queue after : ${JSON.stringify(report.queueCountsAfter)}`);
  console.log(`Report     : ${reportOut}`);
  if (report.dbDiagnostics?.diagnosticsDir) {
    console.log(`Diagnostics: ${report.dbDiagnostics.diagnosticsDir}`);
  }
  if (report.mode === "dry-run") {
    console.log("Set DEV_COMMAND_LOG_PRUNE_APPLY=1 to delete eligible terminal command history.");
  }
}

async function querySingleNumber({ service, dbUser, dbName, sql }) {
  const rows = await execPsql({ service, dbUser, dbName, sql });
  return Number(rows[0]?.[0] ?? 0);
}

async function queryQueueCounts({ service, dbUser, dbName }) {
  const rows = await execPsql({ service, dbUser, dbName, sql: buildQueueCountsSql() });
  const counts = {};
  for (const row of rows) {
    counts[row[0]] = Number(row[1] ?? 0);
  }
  return counts;
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
