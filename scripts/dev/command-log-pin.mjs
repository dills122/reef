import { execFile } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { basename, join, resolve } from "node:path";
import { promisify } from "node:util";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

const execFileAsync = promisify(execFile);
const selectorTypes = new Set(["command_id", "idempotency_prefix", "trace_id", "correlation_id", "client_id", "run_id"]);

export function normalizeSelectorType(raw) {
  const value = String(raw ?? "").trim();
  if (!selectorTypes.has(value)) {
    throw new Error(`invalid retention pin selector type: ${value}`);
  }
  return value;
}

export function buildUpsertPinSql({ pinId, selectorType, selectorValue, reason }) {
  return `
INSERT INTO command_log.retention_pins(pin_id, selector_type, selector_value, reason, updated_at)
VALUES (${sqlString(pinId)}, ${sqlString(selectorType)}, ${sqlString(selectorValue)}, ${sqlString(reason)}, NOW())
ON CONFLICT (selector_type, selector_value) DO UPDATE SET
  reason = EXCLUDED.reason,
  updated_at = EXCLUDED.updated_at
RETURNING pin_id, selector_type, selector_value, reason, created_at, updated_at;
`.trim();
}

export function buildListPinsSql() {
  return `
SELECT pin_id, selector_type, selector_value, reason, created_at, updated_at
FROM command_log.retention_pins
ORDER BY updated_at DESC, pin_id;
`.trim();
}

export function buildDeletePinSql({ selectorType, selectorValue }) {
  return `
DELETE FROM command_log.retention_pins
WHERE selector_type = ${sqlString(selectorType)}
  AND selector_value = ${sqlString(selectorValue)}
RETURNING pin_id, selector_type, selector_value, reason, created_at, updated_at;
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
  const action = env("DEV_COMMAND_LOG_PIN_ACTION", env("DEV_COMMAND_LOG_PIN_SELECTOR_VALUE", "") ? "upsert" : "list");
  const service = env("DEV_COMMAND_LOG_PIN_DB_SERVICE", "postgres");
  const dbUser = env("DEV_COMMAND_LOG_PIN_DB_USER", "reef");
  const dbName = env("DEV_COMMAND_LOG_PIN_DB_NAME", "reef");
  const artifactDir = env("DEV_COMMAND_LOG_PIN_ARTIFACT_DIR", "/tmp");
  const reportOut = env("DEV_COMMAND_LOG_PIN_REPORT_OUT", "/tmp/reef-command-log-pin.json");
  const reportBaseName = basename(reportOut.replace(/\.json$/, ""));

  mkdirSync(artifactDir, { recursive: true });

  const startedAt = new Date().toISOString();
  let rows;
  if (action === "list") {
    rows = await execPsql({ service, dbUser, dbName, sql: buildListPinsSql() });
  } else if (action === "upsert" || action === "delete") {
    const selectorType = normalizeSelectorType(env("DEV_COMMAND_LOG_PIN_SELECTOR_TYPE", "idempotency_prefix"));
    const selectorValue = env("DEV_COMMAND_LOG_PIN_SELECTOR_VALUE", "");
    if (!selectorValue) {
      throw new Error("DEV_COMMAND_LOG_PIN_SELECTOR_VALUE is required for upsert/delete");
    }
    if (action === "upsert") {
      rows = await execPsql({
        service,
        dbUser,
        dbName,
        sql: buildUpsertPinSql({
          pinId: env("DEV_COMMAND_LOG_PIN_ID", `${selectorType}:${selectorValue}`),
          selectorType,
          selectorValue,
          reason: env("DEV_COMMAND_LOG_PIN_REASON", "local retention pin"),
        }),
      });
    } else {
      rows = await execPsql({
        service,
        dbUser,
        dbName,
        sql: buildDeletePinSql({ selectorType, selectorValue }),
      });
    }
  } else {
    throw new Error(`invalid DEV_COMMAND_LOG_PIN_ACTION: ${action}`);
  }

  const report = {
    startedAt,
    finishedAt: new Date().toISOString(),
    action,
    rows: rows.map(toPin),
  };
  const resolvedReportOut = join(artifactDir, basename(reportOut || `${reportBaseName}.json`));
  writeFileSync(resolvedReportOut, JSON.stringify(report, null, 2));
  console.log(`Command Log Pins (${action})`);
  for (const row of report.rows) {
    console.log(`  ${row.selectorType}=${row.selectorValue} (${row.pinId})`);
  }
  console.log(`Report: ${resolvedReportOut}`);
}

async function execPsql({ service, dbUser, dbName, sql }) {
  const { stdout } = await execFileAsync(
    "docker",
    [
      "compose",
      "-f",
      "compose.base.yml",
      "-f",
      "compose.local.yml",
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
    { cwd: process.cwd(), maxBuffer: 10 * 1024 * 1024 },
  );
  return stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split("\t"));
}

function toPin(row) {
  return {
    pinId: row[0] ?? "",
    selectorType: row[1] ?? "",
    selectorValue: row[2] ?? "",
    reason: row[3] ?? "",
    createdAt: row[4] ?? "",
    updatedAt: row[5] ?? "",
  };
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}
