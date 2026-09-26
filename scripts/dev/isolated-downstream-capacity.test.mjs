import assert from "node:assert/strict";
import test from "node:test";
import { validateManifest, buildWorkloadSql, parseTranscript, summarizeCapacity, validateContainers } from "./isolated-downstream-capacity.mjs";

const manifest = { fixtureId: "fixture-1", sourceProjectionName: "runtime-normalized-venue-outcomes", sourceCommands: "1001", sourceMax: "9007199254740993" };
const options = { stage: "lifecycle", projectionName: "market-data-top-of-book", sourceProjectionName: "runtime-normalized-venue-outcomes" };
const snapshot = (dirty = "1001") => ({ databaseGeneration: "generation-1", lifecyclePending: dirty, marketPending: "0", otherClients: "0", sourceMax: manifest.sourceMax, sourceFacts: { orders: "1001" }, watermarks: [{ partition: 0, sequence: manifest.sourceMax, error: "" }] });
function transcript(calls, before = snapshot(), after = snapshot("0")) {
  return ["TASK2_SNAPSHOT before", JSON.stringify(before), "TASK2_END_SNAPSHOT before", "TASK2_READY",
    ...calls.flatMap(([phase, index, rows, ms = "1.250"]) => [...(phase === "redirty" && index === 4 ? ["TASK2_TIMED_START"] : []), `TASK2_BEGIN ${phase} ${index}`, `Time: ${ms} ms${Number(ms) >= 1000 ? " (00:01.200)" : ""}`, `TASK2_END ${phase} ${index} ${rows}`]),
    "TASK2_FINISHED", "TASK2_SNAPSHOT after", JSON.stringify(after), "TASK2_END_SNAPSHOT after", "TASK2_DONE", ""].join("\n");
}

test("lifecycle parser keeps precise counts and timing; counts reconcile independently from commands", () => {
  const parsed = parseTranscript(transcript([["lifecycle", 1, "500"], ["lifecycle", 2, "500"], ["lifecycle", 3, "1"], ["lifecycle", 4, "0", "1200.000"]]));
  const result = summarizeCapacity(options, manifest, parsed, 1300);
  assert.equal(result.pass, true);
  assert.equal(result.dirtyOrders, "1001");
  assert.equal(result.sourceCommands.declared, "1001");
  assert.equal(result.sourceCommands.verifiedByRunner, false);
  assert.equal(result.zeroConfirmationMs, 1200);
  assert.equal(parsed.before.sourceMax, "9007199254740993");
});

test("missing duplicate reordered timings tags or SQL output fail closed", () => {
  const good = transcript([["lifecycle", 1, "500"], ["lifecycle", 2, "0"]]);
  for (const bad of [
    good.replace("Time: 1.250 ms\n", ""),
    good.replace("Time: 1.250 ms", "Time: 1.250 ms\nTime: 1.250 ms"),
    good.replace("TASK2_END lifecycle 1", "TASK2_END lifecycle 2"),
    good.replace("Time: 1.250 ms", "ERROR: transaction aborted"),
    good.replace("Time: 1.250 ms", "Time: NaN ms"),
    good.replace("TASK2_DONE", ""),
    good.replace("TASK2_BEGIN lifecycle 2", "TASK2_BEGIN lifecycle 3"),
  ]) assert.throws(() => parseTranscript(bad));
});

test("early zero unfinished queue source drift generation change and foreign clients invalidate lifecycle", () => {
  for (const [calls, after] of [
    [[["lifecycle", 1, "500"], ["lifecycle", 2, "0"]], snapshot("0")],
    [[["lifecycle", 1, "1001"], ["lifecycle", 2, "0"]], snapshot("0")],
    [[["lifecycle", 1, "500"], ["lifecycle", 2, "500"], ["lifecycle", 3, "1"]], snapshot("0")],
  ]) assert.throws(() => summarizeCapacity(options, manifest, parseTranscript(transcript(calls, snapshot(), after)), 10));
  const calls = [["lifecycle", 1, "500"], ["lifecycle", 2, "500"], ["lifecycle", 3, "1"], ["lifecycle", 4, "0"]];
  for (const update of [{ lifecyclePending: "1" }, { otherClients: "1" }, { databaseGeneration: "restart" }, { sourceFacts: { orders: "1002" } }, { watermarks: [] }]) {
    assert.throws(() => summarizeCapacity(options, manifest, parseTranscript(transcript(calls, snapshot(), { ...snapshot("0"), ...update })), 10));
  }
});

test("market requires all fixed warmup and timed cycles with separate 64-count commits", () => {
  const marketManifest = { ...manifest, instrumentIds: Array.from({ length: 64 }, (_, i) => `instrument-${i}`) };
  const calls = Array.from({ length: 33 }, (_, i) => [["redirty", i + 1, "64"], ["market", i + 1, "64"]]).flat();
  const parsed = parseTranscript(transcript(calls, snapshot("0"), snapshot("0")));
  const result = summarizeCapacity({ ...options, stage: "market" }, marketManifest, parsed, 100);
  assert.equal(result.timedCycles, 30);
  assert.equal(result.dirtyInstrumentOperations, "1920");
  assert.equal(result.uniqueInstruments, 64);
  parsed.calls[10].rows = "63";
  assert.throws(() => summarizeCapacity({ ...options, stage: "market" }, marketManifest, parsed, 100));
});

test("generated SQL has fixed batch separate statements bounded first-zero exit and safe manifest encoding", () => {
  const sql = buildWorkloadSql(options, manifest, snapshot());
  assert.equal((sql.match(/runtime_project_order_lifecycle_state\(500\)/g) ?? []).length, 4);
  assert.match(sql, /\\if :keep_running/);
  assert.match(sql, /\\set keep_running false/);
  assert.doesNotMatch(sql, /\bBEGIN\s*;|\bCOMMIT\s*;|DO\s*\$/);
  const marketManifest = { ...manifest, instrumentIds: Array.from({ length: 64 }, (_, i) => `x'\\;drop-${i}`) };
  const marketSql = buildWorkloadSql({ ...options, stage: "market" }, marketManifest, snapshot("0"));
  assert.equal((marketSql.match(/runtime_project_market_data_snapshots\(/g) ?? []).length, 33);
  assert.doesNotMatch(marketSql, /x'\\;drop/);
  assert.doesNotMatch(marketSql.slice(0, marketSql.indexOf("\\echo TASK2_FINISHED")), /pg_stat_activity|pg_postmaster_start_time/);
  assert.match(marketSql, /WITH inserted AS \(/);
  assert.doesNotMatch(marketSql, /AS MATERIALIZED \(WITH inserted/);
  assert.match(marketSql, /decode\('[a-f0-9]+', 'hex'\)/);
});

test("manifest and project context reject ambiguity or untracked writers", () => {
  assert.throws(() => buildWorkloadSql({ ...options, sourceProjectionName: "wrong" }, manifest, snapshot()));
  assert.throws(() => validateManifest({ ...manifest, sourceProjectionName: undefined }, "lifecycle"));
  assert.throws(() => validateManifest({ ...manifest, sourceMax: 9007199254740992 }, "lifecycle"));
  assert.throws(() => validateManifest({ ...manifest, instrumentIds: Array(64).fill("duplicate") }, "market"));
  const db = { Id: "db", State: { Running: true }, Config: { Labels: { "com.docker.compose.project": "bench", "com.docker.compose.service": "projection-postgres" } } };
  assert.doesNotThrow(() => validateContainers([db], "db", "bench"));
  assert.throws(() => validateContainers([db], "db", "other"));
  assert.throws(() => validateContainers([db, { ...db, Id: "writer", Config: { Labels: { "com.docker.compose.project": "bench", "com.docker.compose.service": "platform-projector-0" } } }], "db", "bench"));
});
