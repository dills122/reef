import assert from "node:assert/strict";
import test from "node:test";
import { assessAuthorityFrontiers, assessTradeProjectionParity, bindingDigest,
  compareExactFirstTrades, compareFinancialBalances, compareTradeBusinessFacts,
  decodeFinancialControl,
  resolveSourceGeneration, uniqueProjectionGeneration,
  verifyRetainedCoverage } from "./settlement-candidate-cohort-check.mjs";

async function* records(rows) { for (const row of rows) yield row; }

test("auto source generation and unique projection generation fail closed", () => {
  const generation = "123e4567-e89b-12d3-a456-426614174000";
  assert.equal(resolveSourceGeneration("auto", generation), generation);
  assert.equal(resolveSourceGeneration(generation, generation), generation);
  assert.throws(() => resolveSourceGeneration("other", generation), /changed/);
  assert.throws(() => resolveSourceGeneration("auto", null), /missing/);
  assert.equal(uniqueProjectionGeneration(["candidate-1"]), "candidate-1");
  assert.throws(() => uniqueProjectionGeneration([]), /missing or ambiguous/);
  assert.throws(() => uniqueProjectionGeneration(["candidate-1", "candidate-2"]), /missing or ambiguous/);
});

test("coverage proves every retained source position exactly once, including empty ranges", async () => {
  const generation = "123e4567-e89b-12d3-a456-426614174000";
  const windows = [["0", "0", "1", "1", generation],
    ["0", "1", "2", "0", generation], ["0", "2", "3", "1", generation]];
  const sourceHeads = { 0: "3", 1: (1n << 48n).toString() };
  const accepted = await verifyRetainedCoverage(windows, sourceHeads,
    records([["0", "1"], ["0", "3"]]), 2, generation);
  assert.deepEqual(accepted.errors, []);
  assert.equal(accepted.sourceOutcomes, 2);
  const missing = await verifyRetainedCoverage(windows, sourceHeads,
    records([["0", "1"], ["0", "2"], ["0", "3"]]), 2, generation);
  assert.match(missing.errors.join(" "), /member count/);
  const overlap = await verifyRetainedCoverage([windows[0], ["0", "0", "3", "1", generation]],
    sourceHeads, records([["0", "1"], ["0", "3"]]), 2, generation);
  assert.match(overlap.errors.join(" "), /gap\/overlap/);
});

test("first-trade merge catches missing, changed and duplicate journal attempts", async () => {
  const source = [["0", "1", "3", "74726164652d31", "6576656e742d31"],
    ["0", "2", "3", "74726164652d32", "6576656e742d32"]];
  assert.deepEqual((await compareExactFirstTrades(records(source), records(source))).mismatches, []);
  const missing = await compareExactFirstTrades(records(source), records(source.slice(0, 1)));
  assert.equal(missing.mismatches.length, 1);
  const duplicate = await compareExactFirstTrades(records(source),
    records([source[0], source[0], source[1]]));
  assert.ok(duplicate.mismatches.length > 0);
});

test("business fact merge compares exact identity and numeric value across source and journal", async () => {
  const source = [["0", "1", "3", "trade", "event", "instrument", "USD", "10.00", "50.000", "2026-09-28 00:00:00+00"]];
  const same = [["0", "1", "3", "trade", "event", "instrument", "USD", "10", "50", "2026-09-28 00:00:00+00"]];
  assert.deepEqual((await compareTradeBusinessFacts(records(source), records(same), [7, 8])).mismatches, []);
  const changed = same.map((row) => row.with(8, "49.99"));
  assert.equal((await compareTradeBusinessFacts(records(source), records(changed), [7, 8])).mismatches.length, 1);
  assert.equal((await compareTradeBusinessFacts(records(source), records([]), [7, 8])).mismatches.length, 1);
});

test("financial trade projection requires exact latest attempt parity", () => {
  assert.deepEqual(assessTradeProjectionParity({ journal_trades: 2, projected_trades: 2,
    mismatches: 0 }, 2), []);
  assert.match(assessTradeProjectionParity({ journal_trades: 2, projected_trades: 2,
    mismatches: 1 }, 2).join(" "), /differs/);
  assert.match(assessTradeProjectionParity({ journal_trades: 2, projected_trades: 1,
    mismatches: 0 }, 2).join(" "), /differs/);
});

function encodedControl(kind, sequence, id, account, amount) {
  const field = (value) => {
    const bytes = Buffer.from(value);
    const length = Buffer.alloc(4); length.writeInt32BE(bytes.length);
    return Buffer.concat([length, bytes]);
  };
  const version = Buffer.alloc(4); version.writeInt32BE(1);
  const ordinal = Buffer.alloc(8); ordinal.writeBigInt64BE(BigInt(sequence));
  const parts = [field("reef.settlement.control.v1"), field(kind), version, ordinal, field(id),
    ...account.map(field), field(amount)];
  if (kind === "FUNDING") parts.push(Buffer.alloc(4));
  return Buffer.concat(parts).toString("hex");
}

test("policy control decoder rejects truncated and trailing immutable bytes", () => {
  const field = (value) => {
    const bytes = Buffer.from(value);
    const length = Buffer.alloc(4); length.writeInt32BE(bytes.length);
    return Buffer.concat([length, bytes]);
  };
  const int = (value) => { const bytes = Buffer.alloc(4); bytes.writeInt32BE(value); return bytes; };
  const long = (value) => { const bytes = Buffer.alloc(8); bytes.writeBigInt64BE(BigInt(value)); return bytes; };
  const payload = Buffer.concat([field("reef.settlement.control.v1"), field("POLICY"),
    int(1), long(1), field("policy-1"), field("run"), field("session"), int(1),
    field("events"), field("source-generation"), int(0), long(0), field("profile"),
    int(1), ...["gross", "T+0", "NONE", "FOUR_LEG", "configured"].map(field)]);
  assert.equal(decodeFinancialControl("POLICY", payload.toString("hex")).id, "policy-1");
  assert.throws(() => decodeFinancialControl("POLICY", payload.subarray(0, -1).toString("hex")),
    /invalid control field length/);
  assert.throws(() => decodeFinancialControl("POLICY", Buffer.concat([payload, Buffer.from([0])]).toString("hex")),
    /trailing bytes/);
});

test("four-leg aggregate balances reconcile exact openings, funding, and financial projection", async () => {
  const buyerCash = ["run", "buyer", "cash-account", "CASH", "USD"];
  const sellerCash = ["run", "seller", "security-account", "CASH", "USD"];
  const sellerSecurity = ["run", "seller", "security-account", "SECURITY", "SEC"];
  const buyerSecurity = ["run", "buyer", "cash-account", "SECURITY", "SEC"];
  const controls = [["1", "open-cash", "OPENING",
    encodedControl("OPENING", 1, "open-cash", buyerCash, "100.00")],
  ["2", "open-security", "OPENING",
    encodedControl("OPENING", 2, "open-security", sellerSecurity, "10")],
  ["3", "fund", "FUNDING",
    encodedControl("FUNDING", 3, "fund", buyerCash, "5.00")]];
  assert.deepEqual(decodeFinancialControl("OPENING", controls[0][3]).account, buyerCash);
  const deltas = [[...buyerCash, "-50.00"], [...sellerCash, "50"],
    [...sellerSecurity, "-5"], [...buyerSecurity, "5"]];
  const projections = [[...buyerCash, "55"], [...sellerCash, "50"],
    [...sellerSecurity, "5"], [...buyerSecurity, "5"]];
  const valid = await compareFinancialBalances(records(controls), records(deltas), records(projections));
  assert.deepEqual(valid.failures, []);
  assert.equal(valid.controls, 3);
  const bad = await compareFinancialBalances(records(controls), records(deltas),
    records(projections.map((row) => row[2] === "cash-account" && row[3] === "CASH"
      ? row.with(5, "54.99") : row)));
  assert.match(bad.failures.join(" "), /financial balance differs/);
  const missing = await compareFinancialBalances(records(controls), records(deltas),
    records(projections.slice(0, 3)));
  assert.match(missing.failures.join(" "), /missing from projection/);
  assert.throws(() => decodeFinancialControl("FUNDING", controls[0][3]), /differs/);
});

test("external finality and both projections must share closed authority frontiers", () => {
  const generation = "123e4567-e89b-12d3-a456-426614174000";
  const binding = { source_generation: generation, event_stream: "REEF_EVENTS",
    command_topic: "REEF_COMMANDS", command_topic_id: "cmd-id",
    venue_event_topic: "REEF_EVENTS", venue_event_topic_id: "event-id" };
  const journal = { next_batch_sequence: "3", last_batch_digest: "a".repeat(64),
    incarnation_id: "incarnation-1", last_control_sequence: "326",
    last_control_digest: "b".repeat(64) };
  const finality = { acknowledged_batch_sequence: "2", acknowledged_batch_digest: "a".repeat(64),
    journal_incarnation_id: "incarnation-1", control_sequence: "326",
    control_digest: "b".repeat(64), source_binding_digest: bindingDigest(binding) };
  const financial = { last_batch_sequence: "2", last_batch_digest: "a".repeat(64),
    journal_incarnation_id: "incarnation-1" };
  const sourceHeads = { 0: "2", 1: (1n << 48n).toString() };
  const market = [{ partition_id: "0", last_stream_sequence: "2", source_generation: generation },
    { partition_id: "1", last_stream_sequence: (1n << 48n).toString(),
      source_generation: generation }];
  const input = { sourceGeneration: generation, binding, journal, finality, financial,
    market, sourceHeads, partitionCount: 2 };
  assert.deepEqual(assessAuthorityFrontiers(input), []);
  assert.match(assessAuthorityFrontiers({ ...input, finality: { ...finality,
    acknowledged_batch_digest: "c".repeat(64) } }).join(" "), /external finality/);
  assert.match(assessAuthorityFrontiers({ ...input, financial: { ...financial,
    last_batch_sequence: "1" } }).join(" "), /financial projection/);
  assert.match(assessAuthorityFrontiers({ ...input, market: market.slice(0, 1) }).join(" "),
    /market projection/);
});
