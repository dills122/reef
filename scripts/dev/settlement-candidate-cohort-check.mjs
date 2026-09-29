#!/usr/bin/env node
/** Closed retained-source cohort check. Broker emptiness and payload digests need recovery proof. */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { createInterface } from "node:readline";
import { pathToFileURL } from "node:url";
import { composeArgs } from "./lib/compose-utils.mjs";

const SAFE_ID = /^[A-Za-z0-9_-]+$/;
const ORIGIN = (partition) => (BigInt(partition) << 48n);

function sqlId(id) {
  if (!SAFE_ID.test(id ?? "")) throw new Error(`unsafe cohort identity: ${id}`);
  return `'${id}'`;
}

async function* rows(service, sql) {
  const child = spawn("docker", composeArgs(["exec", "-T", service, "psql", "-X", "-q",
    "-A", "-t", "-F", "\t", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef",
    "-c", sql]), { stdio: ["ignore", "pipe", "pipe"] });
  let stderr = "";
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
  const closed = new Promise((accept, reject) => {
    child.on("error", reject);
    child.on("close", (code) => code === 0 ? accept() : reject(new Error(
      `${service} cohort query failed (${code}): ${stderr.trim()}`)));
  });
  try {
    for await (const line of createInterface({ input: child.stdout })) {
      if (line !== "") yield line.split("\t");
    }
    await closed;
  } finally {
    if (child.exitCode === null) child.kill("SIGTERM");
  }
}

async function oneJson(service, sql) {
  const result = [];
  for await (const row of rows(service, sql)) result.push(row);
  if (result.length !== 1 || result[0].length !== 1) {
    throw new Error(`${service} cohort query expected one JSON row`);
  }
  return JSON.parse(result[0][0]);
}

export function bindingDigest(binding) {
  const sha = createHash("sha256");
  for (const field of ["reef.settlement.source-topic-binding.v1",
    binding.source_generation, binding.event_stream, binding.command_topic,
    binding.command_topic_id, binding.venue_event_topic, binding.venue_event_topic_id]) {
    const bytes = Buffer.from(field, "utf8");
    const size = Buffer.alloc(4);
    size.writeUInt32BE(bytes.length);
    sha.update(size).update(bytes);
  }
  return sha.digest("hex");
}

export function resolveSourceGeneration(requested, retained) {
  if (!retained || !SAFE_ID.test(retained)) throw new Error("retained source generation missing");
  if (requested !== "auto" && requested !== retained) {
    throw new Error("retained source generation changed");
  }
  return retained;
}

export function uniqueProjectionGeneration(generations) {
  if (generations.length !== 1 || !SAFE_ID.test(generations[0])) {
    throw new Error("candidate financial projection generation missing or ambiguous");
  }
  return generations[0];
}

export async function compareExactFirstTrades(sourceRows, journalRows) {
  const source = sourceRows[Symbol.asyncIterator]();
  const journal = journalRows[Symbol.asyncIterator]();
  let compared = 0;
  const mismatches = [];
  for (;;) {
    const [left, right] = await Promise.all([source.next(), journal.next()]);
    if (left.done && right.done) break;
    if (left.done !== right.done || left.value.join("\t") !== right.value.join("\t")) {
      if (mismatches.length < 10) mismatches.push({ source: left.value ?? null,
        journal: right.value ?? null });
    }
    compared++;
  }
  return { compared, mismatches };
}

export async function verifyRetainedCoverage(windows, sourceHeads, sourceRows,
  partitionCount, sourceGeneration) {
  const errors = [];
  const expected = new Map(Array.from({ length: partitionCount }, (_, partition) =>
    [partition, ORIGIN(partition)]));
  const counts = new Array(windows.length).fill(0);
  const byPartition = new Map();
  windows.forEach((window, index) => {
    const partition = Number(window[0]);
    const from = BigInt(window[1]);
    const through = BigInt(window[2]);
    const members = Number(window[3]);
    if (!expected.has(partition) || window[4] !== sourceGeneration ||
        from !== expected.get(partition) || through <= from ||
        !Number.isSafeInteger(members) || members < 0) {
      errors.push(`source window gap/overlap or invalid member count at index ${index}`);
    }
    expected.set(partition, through);
    if (!byPartition.has(partition)) byPartition.set(partition, []);
    byPartition.get(partition).push({ from, through, members, index });
  });
  const cursor = new Map();
  let sourceOutcomes = 0;
  for await (const [rawPartition, rawSequence] of sourceRows) {
    const partition = Number(rawPartition);
    const sequence = BigInt(rawSequence);
    const items = byPartition.get(partition) ?? [];
    let index = cursor.get(partition) ?? 0;
    while (index < items.length && sequence > items[index].through) index++;
    cursor.set(partition, index);
    if (index >= items.length || sequence <= items[index].from) {
      if (errors.length < 20) errors.push(`retained source outcome uncovered: ${partition}/${sequence}`);
    } else counts[items[index].index]++;
    sourceOutcomes++;
  }
  windows.forEach((window, index) => {
    if (counts[index] !== Number(window[3]) && errors.length < 20) {
      errors.push(`source window ${index} member count ${window[3]} != retained ${counts[index]}`);
    }
  });
  for (let partition = 0; partition < partitionCount; partition++) {
    const head = BigInt(sourceHeads[partition] ?? ORIGIN(partition));
    if (expected.get(partition) < head) {
      errors.push(`journal source frontier behind retained partition ${partition}`);
    }
  }
  return { sourceOutcomes, windows: windows.length, errors,
    journalFrontiers: Object.fromEntries([...expected].map(([p, n]) => [p, n.toString()])) };
}

export function assessAuthorityFrontiers({ sourceGeneration, binding, journal, finality,
  financial, market, sourceHeads, partitionCount }) {
  const failures = [];
  if (!binding || binding.source_generation !== sourceGeneration) {
    failures.push("source topic binding missing or changed generation");
  }
  if (!journal || !finality || !financial) {
    failures.push("journal, finality, or financial projection frontier missing");
    return failures;
  }
  const lastBatch = BigInt(journal.next_batch_sequence) - 1n;
  if (lastBatch !== BigInt(finality.acknowledged_batch_sequence) ||
      journal.last_batch_digest !== finality.acknowledged_batch_digest ||
      journal.incarnation_id !== finality.journal_incarnation_id ||
      journal.last_control_sequence !== finality.control_sequence ||
      journal.last_control_digest !== finality.control_digest ||
      !binding || bindingDigest(binding) !== finality.source_binding_digest) {
    failures.push("external finality differs from journal or retained source binding");
  }
  if (BigInt(financial.last_batch_sequence) !== lastBatch ||
      financial.last_batch_digest !== journal.last_batch_digest ||
      financial.journal_incarnation_id !== journal.incarnation_id) {
    failures.push("financial projection is not at acknowledged journal head");
  }
  if (market.length !== partitionCount) failures.push("market projection lacks a partition frontier");
  for (const row of market) {
    const partition = Number(row.partition_id);
    if (partition < 0 || partition >= partitionCount ||
        row.source_generation !== sourceGeneration ||
        BigInt(row.last_stream_sequence) < BigInt(sourceHeads[partition] ?? ORIGIN(partition))) {
      failures.push(`market projection behind or wrong source at partition ${partition}`);
    }
  }
  return failures;
}

async function inspect(stream, generation, partitionCount) {
  const s = sqlId(stream);
  const sourceGeneration = await oneJson("postgres",
    "SELECT json_build_object('generation',generation::text) FROM runtime.postmatch_source_generation WHERE singleton = TRUE");
  const resolvedGeneration = resolveSourceGeneration(generation, sourceGeneration.generation);
  const g = sqlId(resolvedGeneration);
  const projectionGenerations = [];
  for await (const [candidate] of rows("settlement-postgres", `SELECT projector_generation
    FROM settlement.settlement_journal_projection_checkpoints WHERE event_stream=${s}`)) {
    projectionGenerations.push(candidate);
  }
  const p = sqlId(uniqueProjectionGeneration(projectionGenerations));
  const foreignSource = await oneJson("postgres", `SELECT json_build_object('rows',count(*))
    FROM runtime.canonical_command_outcomes WHERE event_stream<>${s}`);
  const binding = await oneJson("postgres", `SELECT row_to_json(binding) FROM (
    SELECT source_generation::text,event_stream,command_topic,command_topic_id,
           venue_event_topic,venue_event_topic_id FROM runtime.settlement_source_topic_identity
    WHERE event_stream=${s} AND source_generation=${g}::uuid) binding`);
  const sourceHeads = Object.fromEntries(Array.from({ length: partitionCount }, (_, partition) =>
    [partition, ORIGIN(partition).toString()]));
  for await (const [partition, head] of rows("postgres", `SELECT partition_id,max(stream_sequence)
    FROM runtime.canonical_command_outcomes WHERE event_stream=${s}
    GROUP BY partition_id ORDER BY partition_id`)) sourceHeads[partition] = head;
  const journal = await oneJson("settlement-postgres", `SELECT row_to_json(j) FROM (
    SELECT next_batch_sequence,last_batch_digest,incarnation_id,last_control_sequence,
           last_control_digest FROM settlement.settlement_journal_heads WHERE event_stream=${s}) j`);
  const finality = await oneJson("finality-postgres", `SELECT row_to_json(f) FROM (
    SELECT acknowledged_batch_sequence,acknowledged_batch_digest,journal_incarnation_id,
           control_sequence,control_digest,source_binding_digest
    FROM finality.settlement_finality_anchors WHERE event_stream=${s}) f`);
  const financial = await oneJson("settlement-postgres", `SELECT row_to_json(f) FROM (
    SELECT last_batch_sequence,last_batch_digest,journal_incarnation_id
    FROM settlement.settlement_journal_projection_checkpoints
    WHERE event_stream=${s} AND projector_generation=${p}) f`);
  const market = [];
  for await (const [partition_id, last_stream_sequence, source_generation] of rows(
    "postmatch-postgres", `SELECT partition_id,last_stream_sequence,source_generation
    FROM postmatch.matching_market_candidate_frontiers
    WHERE event_stream=${s} AND projector_generation=${p} ORDER BY partition_id`)) {
    market.push({ partition_id, last_stream_sequence, source_generation });
  }
  const failures = assessAuthorityFrontiers({ sourceGeneration: resolvedGeneration, binding, journal,
    finality, financial, market, sourceHeads, partitionCount });
  if (Number(foreignSource.rows) !== 0) failures.push("canonical source contains a foreign event stream");
  const windows = [];
  for await (const window of rows("settlement-postgres", `SELECT partition_id,
    from_exclusive_sequence,through_inclusive_sequence,member_count,source_generation
    FROM settlement.settlement_journal_source_windows WHERE event_stream=${s}
    ORDER BY partition_id,from_exclusive_sequence`)) {
    if (windows.length >= 100_000) throw new Error("cohort exceeds bounded source-window audit cap");
    windows.push(window);
  }
  const coverage = await verifyRetainedCoverage(windows, sourceHeads, rows("postgres",
    `SELECT partition_id,stream_sequence FROM runtime.canonical_command_outcomes
     WHERE event_stream=${s} ORDER BY partition_id,stream_sequence`), partitionCount, resolvedGeneration);
  failures.push(...coverage.errors);
  if (coverage.sourceOutcomes === 0) failures.push("closed source cohort contains no outcomes");
  const sourceTradeSql = `SELECT o.partition_id,o.stream_sequence,(t.ordinality*3)::integer,
    encode(convert_to(t.trade->>'tradeId','UTF8'),'hex'),
    encode(convert_to(t.trade->>'eventId','UTF8'),'hex')
    FROM runtime.canonical_command_outcomes o
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(o.result_payload->'trades','[]'::jsonb))
      WITH ORDINALITY AS t(trade,ordinality)
    WHERE o.event_stream=${s} ORDER BY o.partition_id,o.stream_sequence,t.ordinality`;
  const journalTradeSql = `SELECT partition_id,stream_sequence,effect_ordinal,
    encode(convert_to(trade_id,'UTF8'),'hex'),encode(convert_to(event_id,'UTF8'),'hex')
    FROM settlement.settlement_journal_results
    WHERE event_stream=${s} AND attempt_number=1
    ORDER BY partition_id,stream_sequence,effect_ordinal`;
  const trades = await compareExactFirstTrades(rows("postgres", sourceTradeSql),
    rows("settlement-postgres", journalTradeSql));
  if (trades.mismatches.length) failures.push("source trades differ from first journal attempts");
  if (trades.compared === 0) failures.push("closed source cohort contains no trades");
  const duplicates = await oneJson("settlement-postgres", `SELECT json_build_object(
    'duplicate_first_attempts',(SELECT count(*) FROM (SELECT trade_id
      FROM settlement.settlement_journal_results WHERE event_stream=${s} AND attempt_number=1
      GROUP BY trade_id HAVING count(*) > 1) d),
    'duplicate_settlements',(SELECT count(*) FROM (SELECT trade_id
      FROM settlement.settlement_journal_results WHERE event_stream=${s}
      GROUP BY trade_id HAVING count(*) FILTER (WHERE outcome='SETTLED') > 1) d),
    'attempt_gaps',(SELECT count(*) FROM (SELECT trade_id
      FROM settlement.settlement_journal_results WHERE event_stream=${s}
      GROUP BY trade_id HAVING min(attempt_number)<>1 OR count(*)<>max(attempt_number)) d),
    'wrong_generation_results',(SELECT count(*) FROM settlement.settlement_journal_results
      WHERE event_stream=${s} AND source_generation<>${g}),
    'projected_trades',(SELECT count(*) FROM settlement.settlement_journal_projection_trades
      WHERE event_stream=${s} AND projector_generation=${p}))`);
  if (Number(duplicates.duplicate_first_attempts) !== 0 ||
      Number(duplicates.duplicate_settlements) !== 0 ||
      Number(duplicates.attempt_gaps) !== 0) {
    failures.push("duplicate settlement or missing/duplicated trade attempt");
  }
  if (Number(duplicates.wrong_generation_results) !== 0) {
    failures.push("journal results use a different source generation");
  }
  if (Number(duplicates.projected_trades) !== trades.compared) {
    failures.push("projected trade count differs from matched source first attempts");
  }
  const afterHeads = Object.fromEntries(Array.from({ length: partitionCount }, (_, partition) =>
    [partition, ORIGIN(partition).toString()]));
  for await (const [partition, head] of rows("postgres", `SELECT partition_id,max(stream_sequence)
    FROM runtime.canonical_command_outcomes WHERE event_stream=${s}
    GROUP BY partition_id ORDER BY partition_id`)) afterHeads[partition] = head;
  if (JSON.stringify(afterHeads) !== JSON.stringify(sourceHeads)) {
    failures.push("retained source cohort moved during check");
  }
  const afterJournal = await oneJson("settlement-postgres", `SELECT row_to_json(j) FROM (
    SELECT next_batch_sequence,last_batch_digest,incarnation_id,last_control_sequence,
           last_control_digest FROM settlement.settlement_journal_heads WHERE event_stream=${s}) j`);
  if (JSON.stringify(afterJournal) !== JSON.stringify(journal)) {
    failures.push("journal head moved during check");
  }
  const afterGeneration = await oneJson("postgres",
    "SELECT json_build_object('generation',generation::text) FROM runtime.postmatch_source_generation WHERE singleton = TRUE");
  const afterBinding = await oneJson("postgres", `SELECT row_to_json(binding) FROM (
    SELECT source_generation::text,event_stream,command_topic,command_topic_id,
           venue_event_topic,venue_event_topic_id FROM runtime.settlement_source_topic_identity
    WHERE event_stream=${s} AND source_generation=${g}::uuid) binding`);
  if (afterGeneration.generation !== resolvedGeneration ||
      JSON.stringify(afterBinding) !== JSON.stringify(binding)) {
    failures.push("source generation or retained topic binding moved during check");
  }
  return { status: failures.length ? "fail" : "pass", scope: "closed-retained-source-cohort",
    eventStream: stream, sourceGeneration: resolvedGeneration, sourceHeads, journal, finality,
    financial, market, coverage, firstTrades: trades, duplicates, failures,
    unproven: ["live Kafka topic identity, broker offsets after last retained outcome, and empty-range attestation",
      "full journal batch-chain replay and exact source-member payload digests",
      "same-head source rewrites and replay after crash/takeover",
      "in-load 10000/s throughput, latency, and backlog trend"] };
}

async function main() {
  const [stream, generation, reportOut] = process.argv.slice(2);
  sqlId(stream); sqlId(generation);
  const partitionCount = Number(process.env.POSTMATCH_CANDIDATE_PARTITION_COUNT ?? "16");
  if (!Number.isInteger(partitionCount) || partitionCount !== 16) {
    throw new Error("candidate cohort requires the fixed 16-partition workload");
  }
  const result = await inspect(stream, generation, partitionCount);
  if (reportOut) writeFileSync(reportOut, `${JSON.stringify(result, null, 2)}\n`);
  console.log(JSON.stringify(result));
  if (result.status !== "pass") process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main();
}
