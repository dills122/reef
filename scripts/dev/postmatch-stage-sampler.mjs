#!/usr/bin/env node
// Read-only in-load stage sampler for disposable benchmark runs.
import { spawnSync } from "node:child_process";
import { appendFileSync } from "node:fs";

const compose = ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml", "--profile", "postmatch"];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const literal = (value) => `'${value.replaceAll("'", "''")}'`;

function query(service, sql) {
  const started = performance.now();
  const result = spawnSync("docker", [...compose, "exec", "-T", service, "psql", "-X", "-A", "-t",
    "-F", "\t", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef", "-c", sql],
  { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${service} sample failed: ${result.stderr?.trim() || result.error?.message}`);
  return { rows: result.stdout.trim() ? result.stdout.trim().split("\n").map((line) => line.split("\t")) : [],
    durationMs: Math.round(performance.now() - started) };
}

export function sourceWindowSql(stream, cursors) {
  const values = cursors.map(([partition, sequence]) => `(${partition}, ${sequence})`).join(",");
  return `WITH cursor(partition_id, after_sequence) AS (VALUES ${values})
    SELECT cursor.partition_id, count(outcome.stream_sequence)::text,
      coalesce(max(outcome.stream_sequence), cursor.after_sequence)::text,
      coalesce(sum(jsonb_array_length(coalesce(outcome.result_payload::jsonb->'trades', '[]'::jsonb))), 0)::text
    FROM cursor LEFT JOIN LATERAL (
      SELECT stream_sequence, result_payload FROM runtime.canonical_command_outcomes
      WHERE event_stream = ${literal(stream)} AND partition_id = cursor.partition_id
        AND stream_sequence > cursor.after_sequence
    ) outcome ON TRUE
    GROUP BY cursor.partition_id, cursor.after_sequence ORDER BY cursor.partition_id`;
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

export function summarizeSourceRows(rows, cursors) {
  if (rows.length !== cursors.length) throw new Error("source sample omitted a partition");
  let outcomes = 0n;
  let trades = 0n;
  rows.forEach(([id, count, sequence, tradeCount], index) => {
    if (Number(id) !== cursors[index][0]) throw new Error("source sample partition mismatch");
    if (BigInt(sequence) < BigInt(cursors[index][1])) throw new Error("source sample sequence regressed");
    cursors[index][1] = sequence;
    outcomes += BigInt(count);
    trades += BigInt(tradeCount);
  });
  return { outcomes: outcomes.toString(), trades: trades.toString() };
}

async function main() {
  const [stream, output, settlementText, intervalText = "10000", partitionText = "16"] = process.argv.slice(2);
  if (!/^[A-Za-z0-9_]+$/.test(stream ?? "") || !output || !["true", "false"].includes(settlementText)) {
    throw new Error("usage: postmatch-stage-sampler.mjs EVENT_STREAM OUTPUT_JSONL true|false [INTERVAL_MS] [PARTITIONS]");
  }
  const intervalMs = Number(intervalText);
  const partitionCount = Number(partitionText);
  if (!Number.isInteger(intervalMs) || intervalMs < 1000 || intervalMs > 60000 ||
      !Number.isInteger(partitionCount) || partitionCount < 1 || partitionCount > 64) {
    throw new Error("invalid sampler interval or partition count");
  }
  let running = true;
  process.on("SIGTERM", () => { running = false; });
  const cursors = Array.from({ length: partitionCount }, (_, id) => [id, (BigInt(id) << 48n).toString()]);
  let totalOutcomes = 0n;
  let totalTrades = 0n;
  let previousAt = 0;
  while (running) {
    const sampledAt = Date.now();
    try {
      const source = query("postgres", sourceWindowSql(stream, cursors));
      const sourceMeasuredAt = new Date().toISOString();
      const delta = summarizeSourceRows(source.rows, cursors);
      totalOutcomes += BigInt(delta.outcomes);
      totalTrades += BigInt(delta.trades);
      const sample = { schemaVersion: "reef.postmatchStageSample.v1", sampledAt: new Date(sampledAt).toISOString(),
        intervalMs: previousAt ? sampledAt - previousAt : null, sourceQueryMs: source.durationMs,
        sourceMeasuredAt,
        sourceOutcomesDelta: delta.outcomes, sourceTradesDelta: delta.trades,
        sourceOutcomes: totalOutcomes.toString(), sourceTrades: totalTrades.toString(),
        sourceHeads: Object.fromEntries(cursors.map(([id, sequence]) => [id, sequence])) };
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
      appendFileSync(output, `${JSON.stringify({ schemaVersion: "reef.postmatchStageSample.v1",
        sampledAt: new Date().toISOString(), error: error.message })}\n`);
      throw error;
    }
    if (running) await sleep(Math.max(0, intervalMs - (Date.now() - sampledAt)));
  }
}

if (process.argv[1]?.endsWith("postmatch-stage-sampler.mjs")) await main();
