import assert from "node:assert/strict";
import test from "node:test";
import { assessAuthorityFrontiers, bindingDigest, compareExactFirstTrades,
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
