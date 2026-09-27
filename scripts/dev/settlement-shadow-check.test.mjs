import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import test from "node:test";

import { assess, ledgerMismatchSql } from "./settlement-shadow-check.mjs";

const source = new Map([[0, { count: "3", sequence: "3" }],
  [1, { count: "2", sequence: "281474976710658" }]]);
const generation = "test-generation";
const stages = new Map(["intake", "obligation", "admission", "execution"].flatMap((stage) =>
  [...source].map(([partition, row]) => [`${stage}:${partition}`,
    { generation, sequence: row.sequence }])));
const metrics = {
  intakeTrades: "2", obligations: "2", pending: "0", settled: "2", breaks: "0",
  nonInstant: "0", admissions: "2", maxAdmissionRank: "2", completions: "2",
  attempts: "2", ledgerEntries: "8", badLedgerLegs: "0",
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
    metrics: { ...metrics, pending: "1", completions: "1", ledgerEntries: "3", badLedgerLegs: "1" } },
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

test("PostgreSQL ledger proof rejects wrong owner, asset, direction, amount, and missing leg", {
  skip: process.env.REEF_TEST_SETTLEMENT_SQL !== "1",
  timeout: 60_000,
}, async () => {
  const name = `reef-settlement-proof-${randomUUID().slice(0, 8)}`;
  function docker(args, input) {
    const result = spawnSync("docker", args, { input, encoding: "utf8" });
    if (result.status !== 0) throw new Error(result.stderr || result.error?.message);
    return result.stdout.trim();
  }
  function sql(statement) {
    return docker(["exec", "-i", name, "psql", "-X", "-A", "-t",
      "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef"], `${statement}\n`);
  }
  docker(["run", "-d", "--rm", "--name", name, "--network", "none",
    "-e", "POSTGRES_USER=reef", "-e", "POSTGRES_PASSWORD=reef",
    "-e", "POSTGRES_DB=reef", "postgres:16"]);
  try {
    let ready = false;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      if (spawnSync("docker", ["exec", name, "psql", "-X", "-q", "-U", "reef",
        "-d", "reef", "-c", "SELECT 1"]).status === 0) {
        ready = true;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    assert.ok(ready, "disposable PostgreSQL did not become ready");
    sql(`CREATE SCHEMA settlement;
      CREATE TABLE settlement.canonical_settlement_obligations (
        event_stream text, source_generation text, trade_id text, run_id text, status text,
        buyer_participant_id text, buyer_account_id text, seller_participant_id text,
        seller_account_id text, currency text, instrument_id text,
        cash_amount numeric, quantity_units numeric);
      CREATE TABLE settlement.canonical_transition_ledger_entries (
        event_stream text, source_generation text, trade_id text, attempt_number integer,
        entry_kind text, run_id text, participant_id text, account_id text,
        asset_type text, asset_id text, direction text, quantity numeric);
      INSERT INTO settlement.canonical_settlement_obligations VALUES
        ('test', 'g1', 't1', 'r1', 'SETTLED', 'buyer', 'b1', 'seller', 's1', 'USD', 'ABC', 250, 5);
      INSERT INTO settlement.canonical_transition_ledger_entries VALUES
        ('test', 'g1', 't1', 1, 'BUYER_CASH_DEBIT', 'r1', 'buyer', 'b1', 'CASH', 'USD', 'DEBIT', 250),
        ('test', 'g1', 't1', 1, 'SELLER_CASH_CREDIT', 'r1', 'seller', 's1', 'CASH', 'USD', 'CREDIT', 250),
        ('test', 'g1', 't1', 1, 'SELLER_SECURITY_DEBIT', 'r1', 'seller', 's1', 'SECURITY', 'ABC', 'DEBIT', 5),
        ('test', 'g1', 't1', 1, 'BUYER_SECURITY_CREDIT', 'r1', 'buyer', 'b1', 'SECURITY', 'ABC', 'CREDIT', 5);`);
    const check = () => sql(ledgerMismatchSql("test", "g1"));
    assert.equal(check(), "0");
    for (const [column, wrong, correct] of [
      ["participant_id", "other", "buyer"],
      ["account_id", "wrong", "b1"],
      ["asset_id", "EUR", "USD"],
      ["direction", "CREDIT", "DEBIT"],
      ["quantity", "249", "250"],
    ]) {
      sql(`UPDATE settlement.canonical_transition_ledger_entries SET ${column} = '${wrong}' WHERE entry_kind = 'BUYER_CASH_DEBIT'`);
      assert.equal(check(), "1", `${column} mismatch must fail`);
      sql(`UPDATE settlement.canonical_transition_ledger_entries SET ${column} = '${correct}' WHERE entry_kind = 'BUYER_CASH_DEBIT'`);
      assert.equal(check(), "0", `${column} repair must pass`);
    }
    sql("DELETE FROM settlement.canonical_transition_ledger_entries WHERE entry_kind = 'BUYER_CASH_DEBIT'");
    assert.equal(check(), "1");
  } finally {
    spawnSync("docker", ["rm", "-f", name], { encoding: "utf8" });
  }
});
