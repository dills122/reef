import assert from "node:assert/strict";
import test from "node:test";

import { assess } from "./settlement-shadow-check.mjs";

const source = new Map([[0, { count: "3", sequence: "3" }],
  [1, { count: "2", sequence: "281474976710658" }]]);
const generation = "test-generation";
const stages = new Map(["intake", "obligation", "admission", "execution"].flatMap((stage) =>
  [...source].map(([partition, row]) => [`${stage}:${partition}`,
    { generation, sequence: row.sequence }])));
const metrics = {
  intakeTrades: "2", obligations: "2", pending: "0", settled: "2", breaks: "0",
  nonInstant: "0", admissions: "2", maxAdmissionRank: "2", completions: "2",
  attempts: "2", ledgerEntries: "8", badLedgerTrades: "0",
};

test("complete ranked settlement passes only with trade and ledger proof", () => {
  const result = assess(source, { stages, metrics }, [0, 1], generation);
  assert.deepEqual(result.failures, []);
  assert.equal(result.rows[1].executionSequence, source.get(1).sequence);
});

test("missing or stale transition and malformed ledger fail", () => {
  const incomplete = new Map(stages);
  incomplete.delete("execution:1");
  const result = assess(source, { stages: incomplete,
    metrics: { ...metrics, pending: "1", completions: "1", ledgerEntries: "3", badLedgerTrades: "1" } },
  [0, 1], generation);
  assert.ok(result.failures.some((message) => message.includes("execution frontier missing")));
  assert.ok(result.failures.some((message) => message.includes("pending")));
  assert.ok(result.failures.some((message) => message.includes("completion count")));
  assert.ok(result.failures.some((message) => message.includes("ledger")));
});

test("empty or non-instant cohorts cannot pass", () => {
  const empty = assess(new Map(), { stages: new Map(), metrics: {
    ...metrics, intakeTrades: "0", obligations: "0", settled: "0", breaks: "0",
    attempts: "0", ledgerEntries: "0", admissions: "0", maxAdmissionRank: "0", completions: "0",
    nonInstant: "1",
  } }, [0], generation);
  assert.ok(empty.failures.some((message) => message.includes("source cohort is empty")));
  assert.ok(empty.failures.some((message) => message.includes("no trades")));
  assert.ok(empty.failures.some((message) => message.includes("not instant")));
});

test("break-only cohort cannot establish ledger path", () => {
  const result = assess(source, { stages, metrics: { ...metrics,
    settled: "0", breaks: "2", ledgerEntries: "0" } }, [0, 1], generation);
  assert.ok(result.failures.some((message) => message.includes("no settled trades")));
});
