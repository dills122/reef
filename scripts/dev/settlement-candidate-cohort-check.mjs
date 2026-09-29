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

function canonicalDecimal(value) {
  if (!/^-?\d+(?:\.\d+)?$/.test(value ?? "")) throw new Error(`invalid trade decimal: ${value}`);
  const [integer, fraction = ""] = value.split(".");
  const normalizedInteger = integer.replace(/^(-?)0+(?=\d)/, "$1");
  const normalizedFraction = fraction.replace(/0+$/, "");
  return `${normalizedInteger}${normalizedFraction ? `.${normalizedFraction}` : ""}`;
}

/** Both queries return ordered source identity, exact text facts and numeric value facts. */
export async function compareTradeBusinessFacts(sourceRows, candidateRows, numericIndexes) {
  const source = sourceRows[Symbol.asyncIterator]();
  const candidate = candidateRows[Symbol.asyncIterator]();
  const mismatches = [];
  let compared = 0;
  for (;;) {
    const [left, right] = await Promise.all([source.next(), candidate.next()]);
    if (left.done && right.done) break;
    const expected = left.value?.map((value, index) => numericIndexes.includes(index)
      ? canonicalDecimal(value) : value);
    const actual = right.value?.map((value, index) => numericIndexes.includes(index)
      ? canonicalDecimal(value) : value);
    if (!expected || !actual || expected.length !== actual.length ||
        expected.some((value, index) => value !== actual[index])) {
      if (mismatches.length < 10) mismatches.push({ source: left.value ?? null,
        candidate: right.value ?? null });
    }
    compared++;
  }
  return { compared, mismatches };
}

export function assessTradeProjectionParity(parity, firstTradeCount) {
  const failures = [];
  if (Number(parity.journal_trades) !== firstTradeCount ||
      Number(parity.projected_trades) !== firstTradeCount || Number(parity.mismatches) !== 0) {
    failures.push("financial trade projection differs from latest journal attempts");
  }
  return failures;
}

function addDecimal(left, right) {
  const parts = [left, right].map((value) => {
    if (!/^-?\d+(?:\.\d+)?$/.test(value ?? "") || value.length > 1_000) {
      throw new Error(`invalid balance decimal: ${value}`);
    }
    const [integer, fraction = ""] = value.split(".");
    return { amount: BigInt(`${integer}${fraction}`), scale: fraction.length };
  });
  const scale = Math.max(parts[0].scale, parts[1].scale);
  const amount = parts.reduce((sum, part) =>
    sum + part.amount * (10n ** BigInt(scale - part.scale)), 0n);
  const sign = amount < 0n ? "-" : "";
  const digits = (amount < 0n ? -amount : amount).toString().padStart(scale + 1, "0");
  return canonicalDecimal(`${sign}${digits.slice(0, digits.length - scale)}${scale
    ? `.${digits.slice(-scale)}` : ""}`);
}

function balanceKey(fields) { return JSON.stringify(fields); }

/** Decoder for immutable v1 OPENING/FUNDING bytes emitted by SettlementJournalControlCodec. */
export function decodeFinancialControl(kind, payloadHex) {
  if (!/^(?:[0-9a-f]{2})+$/i.test(payloadHex ?? "")) throw new Error("invalid control payload hex");
  const bytes = Buffer.from(payloadHex, "hex");
  let offset = 0;
  function integer() {
    if (offset + 4 > bytes.length) throw new Error("truncated control integer");
    const value = bytes.readInt32BE(offset); offset += 4; return value;
  }
  function field() {
    const length = integer();
    if (length < 0 || length > 1_048_576 || offset + length > bytes.length) {
      throw new Error("invalid control field length");
    }
    const value = bytes.toString("utf8", offset, offset + length);
    offset += length;
    return value;
  }
  const tag = field();
  const encodedKind = field();
  const version = integer();
  if (offset + 8 > bytes.length) throw new Error("truncated control sequence");
  const sequence = bytes.readBigInt64BE(offset); offset += 8;
  const id = field();
  if (tag !== "reef.settlement.control.v1" || encodedKind !== kind || version !== 1 ||
      sequence <= 0n || !id) throw new Error("control header differs from journal row");
  if (kind === "POLICY") {
    field(); // run ID
    field(); // venue session ID
    const frontiers = integer();
    if (frontiers < 0 || frontiers > 100_000) throw new Error("invalid policy frontier count");
    for (let index = 0; index < frontiers; index++) {
      field(); // event stream
      field(); // source generation
      integer(); // partition
      if (offset + 8 > bytes.length) throw new Error("truncated policy frontier sequence");
      offset += 8;
    }
    field(); // profile ID
    integer(); // policy version
    for (let index = 0; index < 5; index++) field();
    if (offset !== bytes.length) throw new Error("policy control has trailing bytes");
    return { sequence: sequence.toString(), id, kind };
  }
  if (kind !== "OPENING" && kind !== "FUNDING") throw new Error("unknown financial control kind");
  const account = Array.from({ length: 5 }, field);
  const amount = field();
  canonicalDecimal(amount);
  if (kind === "FUNDING") {
    const retries = integer();
    if (retries < 0 || retries > 100_000) throw new Error("invalid funding retry count");
    for (let index = 0; index < retries; index++) field();
  }
  if (offset !== bytes.length) throw new Error("financial control has trailing bytes");
  return { sequence: sequence.toString(), id, kind, account, amount };
}

export async function compareFinancialBalances(controlRows, deltaRows, projectionRows) {
  const expected = new Map();
  const failures = [];
  let controlCount = 0;
  for await (const [rawSequence, id, kind, payloadHex] of controlRows) {
    const control = decodeFinancialControl(kind, payloadHex);
    if (control.sequence !== rawSequence || control.id !== id ||
        BigInt(rawSequence) !== BigInt(controlCount + 1)) {
      if (failures.length < 10) failures.push(`financial control sequence/identity differs at ${rawSequence}`);
    }
    controlCount++;
    if (!control.account) continue;
    const key = balanceKey(control.account);
    if (control.kind === "OPENING") {
      if (expected.has(key)) failures.push(`duplicate opening for ${key}`);
      expected.set(key, control.amount);
    } else {
      if (!expected.has(key)) failures.push(`funding lacks opening for ${key}`);
      expected.set(key, addDecimal(expected.get(key) ?? "0", control.amount));
    }
  }
  for await (const row of deltaRows) {
    const key = balanceKey(row.slice(0, 5));
    expected.set(key, addDecimal(expected.get(key) ?? "0", row[5]));
  }
  let projected = 0;
  for await (const row of projectionRows) {
    const key = balanceKey(row.slice(0, 5));
    const amount = expected.get(key);
    if (amount === undefined || canonicalDecimal(amount) !== canonicalDecimal(row[5])) {
      if (failures.length < 10) failures.push(`financial balance differs for ${key}`);
    }
    expected.delete(key);
    projected++;
  }
  if (expected.size && failures.length < 10) failures.push(`${expected.size} journal balances missing from projection`);
  return { controls: controlCount, projected, unmatched: expected.size, failures };
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
    encode(convert_to(t.trade->>'eventId','UTF8'),'hex'),
    encode(convert_to(t.trade->>'instrumentId','UTF8'),'hex'),
    encode(convert_to(t.trade->>'currency','UTF8'),'hex'),
    (t.trade->>'quantityUnits')::numeric,
    (t.trade->>'quantityUnits')::numeric * (t.trade->>'price')::numeric,
    extract(epoch FROM (t.trade->>'occurredAt')::timestamptz)
    FROM runtime.canonical_command_outcomes o
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(o.result_payload->'trades','[]'::jsonb))
      WITH ORDINALITY AS t(trade,ordinality)
    WHERE o.event_stream=${s} ORDER BY o.partition_id,o.stream_sequence,t.ordinality`;
  const journalTradeSql = `SELECT partition_id,stream_sequence,effect_ordinal,
    encode(convert_to(trade_id,'UTF8'),'hex'),encode(convert_to(event_id,'UTF8'),'hex'),
    encode(convert_to(instrument_id,'UTF8'),'hex'),encode(convert_to(currency,'UTF8'),'hex'),
    quantity_units,cash_amount,extract(epoch FROM occurred_at)
    FROM settlement.settlement_journal_results
    WHERE event_stream=${s} AND attempt_number=1
    ORDER BY partition_id,stream_sequence,effect_ordinal`;
  const trades = await compareTradeBusinessFacts(rows("postgres", sourceTradeSql),
    rows("settlement-postgres", journalTradeSql), [7, 8, 9]);
  if (trades.mismatches.length) failures.push("source business trade facts differ from first journal attempts");
  if (trades.compared === 0) failures.push("closed source cohort contains no trades");
  const sourceMarketSql = `SELECT o.partition_id,o.stream_sequence,(t.ordinality*3)::integer,
    encode(convert_to(t.trade->>'tradeId','UTF8'),'hex'),
    encode(convert_to(t.trade->>'eventId','UTF8'),'hex'),
    encode(convert_to(t.trade->>'executionId','UTF8'),'hex'),
    encode(convert_to(t.trade->>'instrumentId','UTF8'),'hex'),
    encode(convert_to(t.trade->>'currency','UTF8'),'hex'),
    (t.trade->>'quantityUnits')::numeric,(t.trade->>'price')::numeric,
    t.trade->>'occurredAt'
    FROM runtime.canonical_command_outcomes o
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(o.result_payload->'trades','[]'::jsonb))
      WITH ORDINALITY AS t(trade,ordinality)
    WHERE o.event_stream=${s} ORDER BY o.partition_id,o.stream_sequence,t.ordinality`;
  const marketTapeSql = `SELECT partition_id,source_stream_sequence,source_effect_ordinal,
    encode(convert_to(trade_id,'UTF8'),'hex'),encode(convert_to(event_id,'UTF8'),'hex'),
    encode(convert_to(execution_id,'UTF8'),'hex'),encode(convert_to(instrument_id,'UTF8'),'hex'),
    encode(convert_to(currency,'UTF8'),'hex'),quantity_units,price,occurred_at_text
    FROM postmatch.matching_market_candidate_tape
    WHERE event_stream=${s} AND projector_generation=${p}
    ORDER BY partition_id,source_stream_sequence,source_effect_ordinal`;
  const marketTrades = await compareTradeBusinessFacts(rows("postgres", sourceMarketSql),
    rows("postmatch-postgres", marketTapeSql), [8, 9]);
  if (marketTrades.mismatches.length || marketTrades.compared !== trades.compared) {
    failures.push("market tape differs from retained source trades");
  }
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
  const tradeProjectionParity = await oneJson("settlement-postgres", `WITH latest AS (
    SELECT DISTINCT ON (run_id,trade_id) run_id,trade_id,attempt_number,outcome,
      break_reason,batch_sequence,result_index
    FROM settlement.settlement_journal_results WHERE event_stream=${s}
    ORDER BY run_id,trade_id,attempt_number DESC
  ) SELECT json_build_object(
    'journal_trades',count(latest.trade_id),
    'projected_trades',count(projected.trade_id),
    'mismatches',count(*) FILTER (WHERE latest.trade_id IS NULL OR projected.trade_id IS NULL
      OR latest.attempt_number IS DISTINCT FROM projected.attempt_number
      OR latest.outcome IS DISTINCT FROM projected.outcome
      OR latest.break_reason IS DISTINCT FROM projected.break_reason
      OR latest.batch_sequence IS DISTINCT FROM projected.result_batch_sequence
      OR latest.result_index IS DISTINCT FROM projected.result_index))
    FROM latest FULL OUTER JOIN (
      SELECT * FROM settlement.settlement_journal_projection_trades
      WHERE event_stream=${s} AND projector_generation=${p}
    ) projected ON projected.run_id=latest.run_id AND projected.trade_id=latest.trade_id`);
  failures.push(...assessTradeProjectionParity(tradeProjectionParity, trades.compared));
  const controlSql = `SELECT control_sequence,control_id,control_kind,encode(payload,'hex')
    FROM settlement.settlement_journal_controls WHERE event_stream=${s}
    ORDER BY control_sequence`;
  const settledDeltasSql = `SELECT leg.run_id,leg.participant_id,leg.account_id,
    leg.asset_type,leg.asset_id,sum(leg.delta)
    FROM settlement.settlement_journal_results result
    CROSS JOIN LATERAL (VALUES
      (result.run_id,result.buyer_participant_id,result.buyer_account_id,
        'CASH',result.currency,-result.cash_amount),
      (result.run_id,result.seller_participant_id,result.seller_account_id,
        'CASH',result.currency,result.cash_amount),
      (result.run_id,result.seller_participant_id,result.seller_account_id,
        'SECURITY',result.instrument_id,-result.quantity_units),
      (result.run_id,result.buyer_participant_id,result.buyer_account_id,
        'SECURITY',result.instrument_id,result.quantity_units)
    ) AS leg(run_id,participant_id,account_id,asset_type,asset_id,delta)
    WHERE result.event_stream=${s} AND result.outcome='SETTLED'
    GROUP BY leg.run_id,leg.participant_id,leg.account_id,leg.asset_type,leg.asset_id`;
  const projectionBalancesSql = `SELECT run_id,participant_id,account_id,asset_type,asset_id,amount
    FROM settlement.settlement_journal_projection_balances
    WHERE event_stream=${s} AND projector_generation=${p}`;
  const balanceParity = await compareFinancialBalances(rows("settlement-postgres", controlSql),
    rows("settlement-postgres", settledDeltasSql),
    rows("settlement-postgres", projectionBalancesSql));
  failures.push(...balanceParity.failures);
  if (balanceParity.controls !== Number(journal.last_control_sequence)) {
    failures.push("financial control count differs from journal control frontier");
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
    financial, market, coverage, firstTrades: trades, marketTrades, tradeProjectionParity,
    balanceParity,
    duplicates, failures,
    unproven: ["live Kafka topic identity, broker offsets after last retained outcome, and empty-range attestation",
      "full journal batch-chain replay and exact source-member payload digests",
      "source order ownership/account identities, every intermediate balance, and market order/level parity",
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
