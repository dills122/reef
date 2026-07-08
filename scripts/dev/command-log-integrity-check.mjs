import { execFile } from "node:child_process";
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { promisify } from "node:util";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

const execFileAsync = promisify(execFile);

export function buildCommandLogIntegritySummarySql() {
  return `
SELECT violation_type, violation_count
FROM command_log.command_integrity_summary()
ORDER BY violation_type;
`.trim();
}

export function parseIntegritySummaryRows(rows) {
  return rows.map((row) => ({
    violationType: row[0],
    violationCount: Number(row[1] ?? 0),
  }));
}

export function integrityViolations(summary) {
  return summary.filter((row) => row.violationCount > 0);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error?.message ?? error);
    process.exit(1);
  });
}

async function main() {
  loadDotEnv();

  const service = env("DEV_COMMAND_LOG_INTEGRITY_DB_SERVICE", "postgres");
  const dbUser = env("DEV_COMMAND_LOG_INTEGRITY_DB_USER", "reef");
  const dbName = env("DEV_COMMAND_LOG_INTEGRITY_DB_NAME", "reef");
  const reportOut = env("DEV_COMMAND_LOG_INTEGRITY_REPORT_OUT", "/tmp/reef-command-log-integrity.json");

  const rows = await execPsql({
    service,
    dbUser,
    dbName,
    sql: buildCommandLogIntegritySummarySql(),
  });
  const summary = parseIntegritySummaryRows(rows);
  const violations = integrityViolations(summary);
  const report = {
    checkedAt: new Date().toISOString(),
    pass: violations.length === 0,
    summary,
  };

  writeFileSync(reportOut, JSON.stringify(report, null, 2));

  console.log("Command Log Integrity Summary");
  if (summary.length === 0) {
    console.log("No command-log integrity violations.");
  } else {
    for (const row of summary) {
      console.log(`${row.violationType}: ${row.violationCount}`);
    }
  }
  console.log(`Report: ${reportOut}`);

  if (violations.length > 0) {
    throw new Error(`command-log integrity check failed: ${violations.length} violation type(s) present`);
  }
}

async function execPsql({ service, dbUser, dbName, sql }) {
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
      maxBuffer: 10 * 1024 * 1024,
    },
  );
  return stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split("\t"));
}
