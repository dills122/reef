#!/usr/bin/env node
// Closed-cohort settlement consistency check, including canonical source trade membership.
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";

const compose = ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml", "--profile", "postmatch"];
const frontiers = [
  ["intake", "canonical_intake_frontiers"],
  ["obligation", "canonical_obligation_frontiers"],
  ["admission", "canonical_transition_admission_frontiers"],
  ["execution", "canonical_transition_frontiers"],
];

function literal(value) { return `'${value.replaceAll("'", "''")}'`; }

function psqlArgs(service, sql) {
  return [...compose, "exec", "-T", service, "psql", "-X", "-A", "-t", "-F", "\t",
    "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", process.env.PM10_DB || "reef", "-c", sql];
}

function query(service, sql) {
  const result = spawnSync("docker", psqlArgs(service, sql), { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${service} query failed: ${result.stderr?.trim() || result.error?.message}`);
  return result.stdout.trim() ? result.stdout.trim().split("\n").map((line) => line.split("\t")) : [];
}

function sourceSnapshot(stream) {
  const generationRows = query("postgres",
    "SELECT generation::text FROM runtime.postmatch_source_generation WHERE singleton = TRUE");
  if (generationRows.length !== 1) throw new Error("source generation missing");
  const rows = query("postgres", `SELECT partition_id, count(*)::text, max(stream_sequence)::text
    FROM runtime.canonical_command_outcomes WHERE event_stream = ${literal(stream)}
    GROUP BY partition_id ORDER BY partition_id`);
  return { generation: generationRows[0][0], partitions: new Map(rows.map(([partition, count, sequence]) =>
    [Number(partition), { count, sequence }])) };
}

export function ledgerMismatchSql(stream, generation) {
  return `SELECT count(*)::text FROM (
      SELECT 1 FROM settlement.canonical_settlement_obligations o
      CROSS JOIN LATERAL (VALUES
        ('BUYER_CASH_DEBIT', o.buyer_participant_id, o.buyer_account_id,
         'CASH', o.currency, 'DEBIT', o.cash_amount),
        ('SELLER_CASH_CREDIT', o.seller_participant_id, o.seller_account_id,
         'CASH', o.currency, 'CREDIT', o.cash_amount),
        ('SELLER_SECURITY_DEBIT', o.seller_participant_id, o.seller_account_id,
         'SECURITY', o.instrument_id, 'DEBIT', o.quantity_units),
        ('BUYER_SECURITY_CREDIT', o.buyer_participant_id, o.buyer_account_id,
         'SECURITY', o.instrument_id, 'CREDIT', o.quantity_units)
      ) expected(entry_kind, participant_id, account_id, asset_type, asset_id, direction, quantity)
      LEFT JOIN settlement.canonical_transition_ledger_entries l
        ON l.event_stream = o.event_stream AND l.source_generation = o.source_generation
       AND l.trade_id = o.trade_id AND l.attempt_number = 1
       AND l.entry_kind = expected.entry_kind
      WHERE o.event_stream = ${literal(stream)}
        AND o.source_generation = ${literal(generation)} AND o.status = 'SETTLED'
        AND (l.entry_kind IS NULL OR
          (l.run_id, l.participant_id, l.account_id, l.asset_type, l.asset_id,
           l.direction, l.quantity) IS DISTINCT FROM
          (o.run_id, expected.participant_id, expected.account_id,
           expected.asset_type, expected.asset_id, expected.direction, expected.quantity))
    ) bad`;
}

export function attemptMismatchSql(stream, generation) {
  return `SELECT count(*)::text
    FROM settlement.canonical_settlement_obligations o
    LEFT JOIN settlement.canonical_transition_attempts a
      ON a.event_stream = o.event_stream AND a.source_generation = o.source_generation
     AND a.trade_id = o.trade_id AND a.attempt_number = 1
    WHERE o.event_stream = ${literal(stream)}
      AND o.source_generation = ${literal(generation)} AND o.status <> 'PENDING'
      AND (a.trade_id IS NULL OR
        (a.outcome, a.run_id, a.post_trade_profile_id, a.post_trade_policy_version,
         a.partition_id, a.stream_sequence) IS DISTINCT FROM
        (o.status, o.run_id, o.post_trade_profile_id, o.post_trade_policy_version,
         o.partition_id, o.stream_sequence))`;
}

export function sourceTradeMembershipSql(stream, partitions) {
  return `SELECT outcome.partition_id, outcome.stream_sequence,
      ((trade.ordinality - 1) * 3 + 3)::integer AS effect_ordinal,
      trade.value->>'tradeId' AS trade_id
    FROM runtime.canonical_command_outcomes outcome
    CROSS JOIN LATERAL jsonb_array_elements(outcome.result_payload::jsonb->'trades')
      WITH ORDINALITY AS trade(value, ordinality)
    WHERE outcome.event_stream = ${literal(stream)}
      AND outcome.partition_id IN (${partitions.join(",")})
      AND outcome.result_status = 'accepted'
    ORDER BY outcome.partition_id, outcome.stream_sequence, effect_ordinal, trade_id`;
}

export function compareTradeMembership(source, intake, obligation) {
  const failures = [];
  if (source.sha256 !== intake.sha256 || source.bytes !== intake.bytes) {
    failures.push("settlement intake trade membership differs from canonical source");
  }
  if (intake.sha256 !== obligation.sha256 || intake.bytes !== obligation.bytes) {
    failures.push("intake trade and obligation membership differ");
  }
  return failures;
}

function settlementSnapshot(stream, generation, partitions) {
  const ids = partitions.join(",");
  const where = `event_stream = ${literal(stream)} AND source_generation = ${literal(generation)}`;
  const stages = new Map();
  for (const [stage, table] of frontiers) {
    for (const [partition, rowGeneration, sequence] of query("settlement-postgres",
      `SELECT partition_id, source_generation, last_stream_sequence::text
       FROM settlement.${table} WHERE event_stream = ${literal(stream)} AND partition_id IN (${ids})`)) {
      stages.set(`${stage}:${partition}`, { generation: rowGeneration, sequence });
    }
  }
  const counts = query("settlement-postgres", `SELECT
    (SELECT count(*) FROM settlement.canonical_trade_intake WHERE ${where})::text,
    (SELECT count(*) FROM settlement.canonical_settlement_obligations WHERE ${where})::text,
    (SELECT count(*) FROM settlement.canonical_settlement_obligations WHERE ${where} AND status = 'PENDING')::text,
    (SELECT count(*) FROM settlement.canonical_settlement_obligations WHERE ${where} AND status = 'SETTLED')::text,
    (SELECT count(*) FROM settlement.canonical_settlement_obligations WHERE ${where} AND status = 'BREAK')::text,
    (SELECT count(*) FROM settlement.canonical_settlement_obligations WHERE ${where} AND post_trade_mode <> 'instant-post-trade')::text,
    (SELECT count(*) FROM settlement.canonical_transition_admissions WHERE ${where})::text,
    (SELECT coalesce(max(admission_rank), 0) FROM settlement.canonical_transition_admissions WHERE ${where})::text,
    (SELECT count(*) FROM settlement.canonical_transition_admission_completions WHERE ${where})::text,
    (SELECT count(*) FROM settlement.canonical_transition_attempts WHERE ${where})::text,
    (SELECT count(*) FROM settlement.canonical_transition_ledger_entries WHERE ${where})::text`);
  if (counts.length !== 1 || counts[0].length !== 11) throw new Error("settlement counts are incomplete");
  const names = ["intakeTrades", "obligations", "pending", "settled", "breaks", "nonInstant",
    "admissions", "maxAdmissionRank", "completions", "attempts", "ledgerEntries"];
  const metrics = Object.fromEntries(names.map((name, index) => [name, counts[0][index]]));
  if (BigInt(metrics.pending) === 0n && BigInt(metrics.obligations) > 0n) {
    const bad = query("settlement-postgres", ledgerMismatchSql(stream, generation));
    metrics.badLedgerLegs = bad[0]?.[0] ?? "unknown";
    const badAttempts = query("settlement-postgres", attemptMismatchSql(stream, generation));
    metrics.badAttemptOutcomes = badAttempts[0]?.[0] ?? "unknown";
  }
  return { stages, metrics };
}

export function assess(source, target, partitions, generation) {
  const failures = [];
  const assigned = new Set(partitions);
  for (const partition of source.keys()) {
    if (!assigned.has(partition)) failures.push(`source partition ${partition} is not assigned`);
  }
  const rows = partitions.map((partition) => {
    const origin = (BigInt(partition) << 48n).toString();
    const sourceRow = source.get(partition) ?? { count: "0", sequence: origin };
    const row = { partition, sourceCount: sourceRow.count, sourceSequence: sourceRow.sequence };
    if (sourceRow.count !== "0") {
      for (const [stage] of frontiers) {
        const saved = target.stages.get(`${stage}:${partition}`);
        row[`${stage}Sequence`] = saved?.sequence ?? null;
        if (!saved) failures.push(`${stage} frontier missing for partition ${partition}`);
        else if (saved.generation !== generation) failures.push(`${stage} generation mismatch for partition ${partition}`);
        else if (BigInt(saved.sequence) !== BigInt(sourceRow.sequence)) {
          failures.push(`${stage} frontier differs from source for partition ${partition}`);
        }
      }
    }
    return row;
  });
  if (rows.every((row) => row.sourceCount === "0")) failures.push("source cohort is empty");
  const n = Object.fromEntries(Object.entries(target.metrics).map(([key, value]) =>
    [key, value === "unknown" ? null : BigInt(value)]));
  if (n.intakeTrades === 0n) failures.push("settlement cohort contains no trades");
  if (n.settled === 0n) failures.push("settlement cohort contains no settled trades or ledger postings");
  if (n.intakeTrades !== n.obligations) failures.push("intake trade and obligation counts differ");
  if (n.pending !== 0n) failures.push("settlement obligations remain pending");
  if (n.breaks !== 0n) failures.push("funded settlement cohort contains breaks");
  if (n.nonInstant !== 0n) failures.push("settlement profile is not instant-post-trade");
  if (n.settled + n.breaks !== n.obligations) failures.push("settlement outcomes do not cover obligations");
  if (n.attempts !== n.settled + n.breaks) failures.push("transition attempts do not cover outcomes");
  if (n.badAttemptOutcomes !== undefined && n.badAttemptOutcomes !== 0n) {
    failures.push("transition attempts differ from obligation outcomes");
  }
  if (n.admissions === 0n || n.admissions !== n.completions || n.maxAdmissionRank !== n.admissions) {
    failures.push("admission rank or completion count differs");
  }
  if (n.ledgerEntries !== n.settled * 4n) failures.push("settled trade ledger count is not four per trade");
  if (n.badLedgerLegs !== undefined && n.badLedgerLegs !== 0n) failures.push("ledger legs differ from settled obligations");
  return { rows, metrics: target.metrics, failures };
}

async function membershipHash(service, sql) {
  const child = spawn("docker", psqlArgs(service, `COPY (${sql}) TO STDOUT WITH (FORMAT csv)`),
    { stdio: ["ignore", "pipe", "pipe"] });
  const hash = createHash("sha256");
  let bytes = 0;
  let stderr = "";
  child.stdout.on("data", (chunk) => { hash.update(chunk); bytes += chunk.length; });
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
  const code = await new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("close", resolve);
  });
  if (code !== 0) throw new Error(`${service} membership query failed: ${stderr.trim()}`);
  return { sha256: hash.digest("hex"), bytes };
}

async function main() {
  const [stream, partitionText, output, waitText = "300"] = process.argv.slice(2);
  if (!stream || !/^[A-Za-z0-9_]+$/.test(stream) || !output) {
    throw new Error("usage: settlement-shadow-check.mjs EVENT_STREAM PARTITIONS OUTPUT_JSON [WAIT_SECONDS]");
  }
  const partitions = partitionText?.split(",").map(Number);
  if (!partitions?.length || partitions.some((p) => !Number.isInteger(p) || p < 0 || p > 32767) ||
      new Set(partitions).size !== partitions.length) throw new Error("partitions must be distinct IDs in 0..32767");
  const waitSeconds = Number(waitText);
  if (!Number.isInteger(waitSeconds) || waitSeconds < 0 || waitSeconds > 600) throw new Error("invalid wait seconds");
  const report = { schemaVersion: "reef.settlementShadowDiagnostic.v1", eventStream: stream,
    partitions, sourceTradeMembershipVerified: false,
    status: "fail", checkedAt: null, failures: [] };
  try {
    const initial = sourceSnapshot(stream);
    report.generation = initial.generation;
    const deadline = Date.now() + waitSeconds * 1000;
    do {
      Object.assign(report, assess(initial.partitions,
        settlementSnapshot(stream, initial.generation, partitions), partitions, initial.generation));
      if (report.failures.length === 0 || Date.now() >= deadline) break;
      await new Promise((resolve) => setTimeout(resolve, 2000));
    } while (true);
    if (report.failures.length === 0) {
      const where = `event_stream = ${literal(stream)} AND source_generation = ${literal(initial.generation)}`;
      const columns = "partition_id, stream_sequence, effect_ordinal, trade_id";
      report.sourceTradeMembership = await membershipHash("postgres", sourceTradeMembershipSql(stream, partitions));
      report.intakeMembership = await membershipHash("settlement-postgres", `SELECT ${columns}
        FROM settlement.canonical_trade_intake WHERE ${where} ORDER BY ${columns}`);
      report.obligationMembership = await membershipHash("settlement-postgres", `SELECT ${columns}
        FROM settlement.canonical_settlement_obligations WHERE ${where} ORDER BY ${columns}`);
      report.failures.push(...compareTradeMembership(report.sourceTradeMembership,
        report.intakeMembership, report.obligationMembership));
      report.sourceTradeMembershipAfter = await membershipHash("postgres", sourceTradeMembershipSql(stream, partitions));
      if (report.sourceTradeMembership.sha256 !== report.sourceTradeMembershipAfter.sha256 ||
          report.sourceTradeMembership.bytes !== report.sourceTradeMembershipAfter.bytes) {
        report.failures.push("canonical source trade membership changed during settlement check");
      }
      const latest = sourceSnapshot(stream);
      if (latest.generation !== initial.generation || latest.partitions.size !== initial.partitions.size ||
          [...initial.partitions].some(([partition, row]) => {
            const after = latest.partitions.get(partition);
            return after?.count !== row.count || after?.sequence !== row.sequence;
          })) report.failures.push("canonical source changed during settlement check");
    }
    report.sourceTradeMembershipVerified = report.failures.length === 0;
    report.status = report.failures.length ? "fail" : "pass";
  } catch (error) {
    report.failures.push(error.message);
  } finally {
    report.checkedAt = new Date().toISOString();
    writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
    console.log(`settlement shadow diagnostic: ${report.status}; report=${output}`);
    for (const failure of report.failures) console.error(failure);
  }
  if (report.status !== "pass") process.exitCode = 1;
}

if (process.argv[1]?.endsWith("settlement-shadow-check.mjs")) await main();
