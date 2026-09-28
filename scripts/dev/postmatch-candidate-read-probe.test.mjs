import assert from "node:assert/strict";
import { test } from "node:test";
import { hasCompleteAsOf, parseArgs, probeOnce, summarizeSamples } from "./postmatch-candidate-read-probe.mjs";

const sample = (name, latencyMs, ageUpperBoundMs, overrides = {}) => ({
  name, latencyMs, ok: true,
  valuePresent: true,
  asOf: { projectedSourceSequence: 3, currentAtObservation: true },
  currentAtObservation: true,
  sourceAgeUpperBoundMs: ageUpperBoundMs,
  ...overrides,
});

test("read probe requires loopback HTTP without embedded credentials", () => {
  assert.throws(() => parseArgs(["--url", "book=https://example.com/book"]), /loopback/);
  assert.throws(() => parseArgs(["--url", "book=http://user:pass@127.0.0.1/book"]), /loopback/);
  assert.throws(() => parseArgs(["--url", "book=http://10.0.0.2/book"]), /loopback/);
  assert.equal(parseArgs(["--url", "book=http://127.0.0.1:8080/book"]).urls.get("book"),
    "http://127.0.0.1:8080/book");
  assert.equal(parseArgs(["--url", "balance=http://127.0.0.1/balance",
    "--participant-id", "participant-1"]).participantId, "participant-1");
  assert.throws(() => parseArgs(["--url", "balance=http://127.0.0.1/balance",
    "--participant-id", "participant-1\r\nAuthorization: x"]), /header-safe/);
});

test("numeric gates must be complete and cover every candidate read", () => {
  assert.throws(() => parseArgs(["--url", "book=http://localhost/book",
    "--latency-p95-ms", "100"]), /both positive/);
  const gates = { latencyP95Ms: 100, latencyP99Ms: 150 };
  const partial = ["book", "depth", "tape"].map((name) => sample(name, 50, 1000));
  assert.equal(summarizeSamples(partial, gates).readGatePassed, false);
  const complete = ["book", "depth", "tape", "balance", "status"]
    .map((name) => sample(name, 50, 1000));
  assert.equal(summarizeSamples(complete, gates).readGatePassed, true);
  const lagging = complete.map((row) => row.name === "book" ?
    { ...row, currentAtObservation: false } : row);
  assert.equal(summarizeSamples(lagging, gates).readGatePassed, true);
  assert.equal(summarizeSamples(lagging, gates).byName.book.notCurrent, 1);
  assert.equal(summarizeSamples(complete, gates).ageGateAuthority,
    "separate source-to-visible window-commit measurements");
});

test("failures and lag remain visible in distribution summary", () => {
  const rows = [sample("book", 10, 100), sample("book", 20, 200),
    sample("book", 30, 300, { currentAtObservation: false }),
    sample("book", 40, 400, { currentAtObservation: null }),
    { name: "book", latencyMs: 12, ok: false, asOf: null }];
  const summary = summarizeSamples(rows);
  assert.equal(summary.byName.book.samples, 5);
  assert.equal(summary.byName.book.failures, 1);
  assert.equal(summary.byName.book.notCurrent, 2);
  assert.equal(summary.byName.book.latencyP95Ms, 40);
  assert.equal(summary.byName.book.sourceAgeUpperBoundP95Ms, 400);
  assert.equal(summary.readGatePassed, false);
});

test("probe rejects a response without a complete as-of frontier", () => {
  assert.equal(hasCompleteAsOf("book", { projectedSourceSequence: 3,
    currentAtObservation: true }), false);
  assert.equal(hasCompleteAsOf("book", { projectedSourceSequence: 3,
    observedSourceSequence: 3, sourceLagPositions: 0,
    sourceGeneration: "source", projectorGeneration: "market",
    currentAtObservation: true }), true);
  assert.equal(hasCompleteAsOf("balance", { batchSequence: 4,
    observedJournalSequence: 4, journalLagBatches: 0,
    journalIncarnationId: "incarnation", currentAtObservation: true }), true);
});

test("requests include non-secret client identity and private participant scope", async () => {
  const calls = [];
  const fakeFetch = async (_url, options) => {
    calls.push(options.headers);
    return { ok: true, status: 200, json: async () => ({ asOf: {
      batchSequence: 4, observedJournalSequence: 4, journalLagBatches: 0,
      journalIncarnationId: "incarnation", currentAtObservation: true } }) };
  };
  const privateRead = await probeOnce("balance", "http://127.0.0.1/balance", 1000,
    "participant-1", fakeFetch);
  await probeOnce("book", "http://127.0.0.1/book", 1000,
    "participant-1", fakeFetch);
  assert.equal(privateRead.ok, true);
  assert.deepEqual(calls[0], { "X-Client-Id": "candidate-read-probe",
    "X-Participant-Id": "participant-1" });
  assert.deepEqual(calls[1], { "X-Client-Id": "candidate-read-probe" });
});

test("status admin credential is sent only to loopback HTTP", async () => {
  const prior = process.env.ADMIN_API_TOKEN;
  process.env.ADMIN_API_TOKEN = "probe-test-secret";
  try {
    const headers = [];
    const fakeFetch = async (_url, options) => {
      headers.push(options.headers);
      return { ok: true, status: 200, json: async () => ({ asOf: {
        batchSequence: 4, observedJournalSequence: 4, journalLagBatches: 0,
        journalIncarnationId: "incarnation", currentAtObservation: true } }) };
    };
    await probeOnce("status", "http://127.0.0.1/status", 1000, null, fakeFetch);
    await probeOnce("status", "https://example.com/status", 1000, null, fakeFetch);
    assert.equal(headers[0].Authorization, "Bearer probe-test-secret");
    assert.equal(headers[1].Authorization, undefined);
  } finally {
    if (prior === undefined) delete process.env.ADMIN_API_TOKEN;
    else process.env.ADMIN_API_TOKEN = prior;
  }
});

test("private read gate requires at least one real balance and settlement value", () => {
  const gates = { latencyP95Ms: 100, latencyP99Ms: 200 };
  const missing = ["book", "depth", "tape", "balance", "status"]
    .map((name) => sample(name, 25, null, { valuePresent: name !== "status" }));
  assert.equal(summarizeSamples(missing, gates).readGatePassed, false);
  assert.equal(summarizeSamples(missing, gates).byName.status.valuesPresent, 0);
});

test("tape probe discovers a durable trade identity for status reads", async () => {
  const fakeFetch = async () => ({ ok: true, status: 200, json: async () => ({
    trades: [{ tradeId: "trade-42" }],
    asOf: { projectedSourceSequence: 3, observedSourceSequence: 3,
      sourceLagPositions: 0, sourceGeneration: "source", projectorGeneration: "market",
      currentAtObservation: true },
  }) });
  const result = await probeOnce("tape", "http://127.0.0.1/tape", 1000, null, fakeFetch);
  assert.equal(result.ok, true);
  assert.equal(result.discoveredTradeId, "trade-42");
});
