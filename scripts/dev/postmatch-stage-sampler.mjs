#!/usr/bin/env node
// Read-only in-load stage sampler for disposable benchmark runs.
import { spawnSync } from "node:child_process";
import { appendFileSync } from "node:fs";

const compose = ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml", "--profile", "postmatch"];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function query(service, sql) {
  const started = performance.now();
  const result = spawnSync("docker", [...compose, "exec", "-T", service, "psql", "-X", "-A", "-t",
    "-F", "\t", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef", "-c", sql],
  { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${service} sample failed: ${result.stderr?.trim() || result.error?.message}`);
  return { rows: result.stdout.trim() ? result.stdout.trim().split("\n").map((line) => line.split("\t")) : [],
    durationMs: Math.round(performance.now() - started) };
}

export function sourceStageSql() {
  // Fresh benchmark volumes contain one source cohort. PostgreSQL's insert
  // counter avoids scanning and decoding every canonical result during load.
  return `SELECT coalesce(max(n_tup_ins), 0)::text, count(*)::text
    FROM pg_stat_user_tables
    WHERE schemaname = 'runtime' AND relname = 'canonical_command_outcomes'`;
}

export function settlementStageSql() {
  // Fresh benchmark volumes contain one cohort. pg_stat reads are cheap and
  // approximate during load; final closed-cohort SQL is the exact proof.
  const inserted = (table) => `(SELECT coalesce(max(n_tup_ins), 0)::text FROM pg_stat_user_tables
    WHERE schemaname = 'settlement' AND relname = '${table}')`;
  return `SELECT
    ${inserted("canonical_trade_intake")},
    ${inserted("canonical_settlement_obligations")},
    ${inserted("canonical_transition_admissions")},
    ${inserted("canonical_transition_admission_completions")},
    ${inserted("canonical_transition_attempts")},
    ${inserted("canonical_transition_ledger_entries")},
    (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
       AND state = 'active' AND wait_event_type = 'Lock')::text,
    (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
       AND state = 'active' AND wait_event = 'transactionid')::text,
    (SELECT count(*) FROM pg_stat_user_tables WHERE schemaname = 'settlement'
       AND relname IN ('canonical_trade_intake', 'canonical_settlement_obligations',
         'canonical_transition_admissions', 'canonical_transition_admission_completions',
         'canonical_transition_attempts', 'canonical_transition_ledger_entries'))::text`;
}

export function summarizeSourceRows(rows) {
  if (rows.length !== 1 || rows[0].length !== 2 || rows[0][1] !== "1") {
    throw new Error("canonical source outcome statistics are unavailable");
  }
  const outcomes = BigInt(rows[0][0]);
  if (outcomes < 0n) throw new Error("canonical source outcome statistics regressed");
  return outcomes.toString();
}

async function main() {
  const [stream, output, settlementText, intervalText = "60000"] = process.argv.slice(2);
  if (!/^[A-Za-z0-9_]+$/.test(stream ?? "") || !output || !["true", "false"].includes(settlementText)) {
    throw new Error("usage: postmatch-stage-sampler.mjs EVENT_STREAM OUTPUT_JSONL true|false [INTERVAL_MS]");
  }
  const intervalMs = Number(intervalText);
  if (!Number.isInteger(intervalMs) || intervalMs < 1000 || intervalMs > 60000) throw new Error("invalid sampler interval");
  let running = true;
  process.on("SIGTERM", () => { running = false; });
  let previousOutcomes = null;
  let previousAt = 0;
  while (running) {
    const sampledAt = Date.now();
    try {
      const source = query("postgres", sourceStageSql());
      const sourceMeasuredAt = new Date().toISOString();
      const outcomes = BigInt(summarizeSourceRows(source.rows));
      if (previousOutcomes != null && outcomes < previousOutcomes) {
        throw new Error("canonical source outcome statistics regressed");
      }
      const sample = { schemaVersion: "reef.postmatchStageSample.v2", eventStream: stream,
        sampledAt: new Date(sampledAt).toISOString(),
        intervalMs: previousAt ? sampledAt - previousAt : null, sourceQueryMs: source.durationMs,
        sourceMeasuredAt, sourceOutcomesDelta: previousOutcomes == null ? null : (outcomes - previousOutcomes).toString(),
        sourceOutcomes: outcomes.toString(), sourceMeasure: "pg_stat_user_tables.n_tup_ins" };
      previousOutcomes = outcomes;
      if (settlementText === "true") {
        const target = query("settlement-postgres", settlementStageSql());
        if (target.rows.length !== 1 || target.rows[0].length !== 9 || target.rows[0][8] !== "6") {
          throw new Error("settlement sampler schema is incomplete");
        }
        const keys = ["intakeTrades", "obligations", "admissions", "completions", "attempts",
          "ledgerEntries", "lockWaitSessions", "transactionIdWaitSessions"];
        sample.settlement = Object.fromEntries(keys.map((key, index) => [key, target.rows[0][index]]));
        sample.settlementQueryMs = target.durationMs;
        sample.settlementMeasuredAt = new Date().toISOString();
      }
      appendFileSync(output, `${JSON.stringify(sample)}\n`);
      previousAt = sampledAt;
    } catch (error) {
      appendFileSync(output, `${JSON.stringify({ schemaVersion: "reef.postmatchStageSample.v2",
        sampledAt: new Date().toISOString(), error: error.message })}\n`);
      throw error;
    }
    if (running) await sleep(Math.max(0, intervalMs - (Date.now() - sampledAt)));
  }
}

if (process.argv[1]?.endsWith("postmatch-stage-sampler.mjs")) await main();
