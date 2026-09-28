import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { assess, assessFrontiers, attemptMismatchSql, compareTradeMembership, ledgerMismatchSql,
  settlementCountsSql,
  sourceTradeMembershipSql } from "./settlement-shadow-check.mjs";
import { settlementStageSql } from "./postmatch-stage-sampler.mjs";
import { assessDependencyGraph, dependencyGraphSql } from "./settlement-dependency-graph-check.mjs";

const source = new Map([[0, { count: "3", sequence: "3" }],
  [1, { count: "2", sequence: "281474976710658" }]]);
const generation = "test-generation";
const stages = new Map(["intake", "obligation", "admission", "execution"].flatMap((stage) =>
  [...source].map(([partition, row]) => [`${stage}:${partition}`,
    { generation, sequence: row.sequence }])));
const metrics = {
  intakeTrades: "2", obligations: "2", pending: "0", settled: "2", breaks: "0",
  nonInstant: "0", admissions: "2", maxAdmissionRank: "2", completions: "2",
  attempts: "2", ledgerEntries: "8", badLedgerLegs: "0", badAttemptOutcomes: "0",
};

test("complete ranked settlement passes only with trade and ledger proof", () => {
  const result = assess(source, { stages, metrics }, [0, 1], generation);
  assert.deepEqual(result.failures, []);
  assert.equal(result.rows[1].executionSequence, source.get(1).sequence);
});

test("frontier readiness rejects lag before exact metrics are available", () => {
  const lagging = new Map(stages);
  lagging.set("execution:1", { generation, sequence: "281474976710657" });
  assert.match(assessFrontiers(source, lagging, [0, 1], generation).failures.join(" "),
    /execution frontier differs from source/);
  assert.deepEqual(assessFrontiers(source, stages, [0, 1], generation).failures, []);
});

test("CLI polls only frontiers while lagged, then runs exact proof once", () => {
  const dir = mkdtempSync(join(tmpdir(), "reef-settlement-check-"));
  const docker = join(dir, "docker");
  const calls = join(dir, "calls");
  const polls = join(dir, "polls");
  writeFileSync(docker, `#!/usr/bin/env node
const fs = require("node:fs");
const sql = process.argv.at(-1);
const target = process.argv.includes("settlement-postgres");
let kind;
if (sql.startsWith("COPY (")) kind = "membership";
else if (sql.includes("postmatch_source_generation")) kind = "generation";
else if (sql.includes("GROUP BY partition_id")) kind = "source";
else if (sql.includes("UNION ALL")) kind = "frontiers";
else if (sql.includes("WITH obligation_counts")) kind = "counts";
else if (sql.includes("CROSS JOIN LATERAL (VALUES")) kind = "ledger";
else if (sql.includes("LEFT JOIN settlement.canonical_transition_attempts")) kind = "attempt";
else process.exit(2);
fs.appendFileSync(process.env.REEF_FAKE_CALLS, kind + "\\n");
if (kind === "generation") process.stdout.write("gen\\n");
if (kind === "source") process.stdout.write("0\\t2\\t2\\n");
if (kind === "frontiers") {
  const poll = fs.existsSync(process.env.REEF_FAKE_POLLS)
    ? Number(fs.readFileSync(process.env.REEF_FAKE_POLLS, "utf8")) + 1 : 1;
  fs.writeFileSync(process.env.REEF_FAKE_POLLS, String(poll));
  for (const stage of ["intake", "obligation", "admission", "execution"]) {
    if (stage === "execution" && (process.env.REEF_FAKE_STUCK === "1" ||
      poll === 1 && process.env.REEF_FAKE_LAG === "1")) continue;
    process.stdout.write(stage + "\\t0\\tgen\\t2\\n");
  }
}
if (kind === "counts") process.stdout.write("1\\t1\\t0\\t1\\t0\\t0\\t1\\t1\\t1\\t1\\t4\\n");
if (kind === "ledger") process.stdout.write(process.env.REEF_FAKE_BAD_LEDGER === "1" ? "1\\n" : "0\\n");
if (kind === "attempt") process.stdout.write("0\\n");
if (kind === "membership") process.stdout.write(target && process.env.REEF_FAKE_MISSING_TRADE === "1" &&
  sql.includes("canonical_trade_intake") ? "" : "0,2,3,t1\\n");
`);
  chmodSync(docker, 0o755);
  const run = (lag, badLedger, missingTrade = false, stuck = false) => {
    writeFileSync(calls, "");
    writeFileSync(polls, "0");
    const output = join(dir, badLedger ? "bad-ledger.json" : missingTrade ? "bad-trade.json" :
      stuck ? "stuck.json" : "pass.json");
    const result = spawnSync(process.execPath,
      ["scripts/dev/settlement-shadow-check.mjs", "REEF_EVENTS_TEST", "0", output, lag ? "3" : "0"],
      { encoding: "utf8", env: { ...process.env, PATH: `${dir}:${process.env.PATH}`,
        REEF_FAKE_CALLS: calls, REEF_FAKE_POLLS: polls,
        REEF_FAKE_LAG: lag ? "1" : "0", REEF_FAKE_BAD_LEDGER: badLedger ? "1" : "0",
        REEF_FAKE_MISSING_TRADE: missingTrade ? "1" : "0",
        REEF_FAKE_STUCK: stuck ? "1" : "0" } });
    return { result, report: JSON.parse(readFileSync(output, "utf8")),
      calls: readFileSync(calls, "utf8").trim().split("\n") };
  };
  const pass = run(true, false);
  assert.equal(pass.result.status, 0, pass.result.stderr);
  assert.equal(pass.report.sourceTradeMembershipVerified, true);
  assert.equal(pass.calls.filter((kind) => kind === "frontiers").length, 2);
  assert.equal(pass.calls.filter((kind) => kind === "counts").length, 1);
  assert.ok(pass.calls.indexOf("counts") > pass.calls.lastIndexOf("frontiers"));
  assert.equal(pass.calls.filter((kind) => kind === "membership").length, 4);
  const bad = run(false, true);
  assert.equal(bad.result.status, 1);
  assert.match(bad.report.failures.join(" "), /ledger legs differ/);
  assert.equal(bad.calls.filter((kind) => kind === "counts").length, 1);
  const missing = run(false, false, true);
  assert.equal(missing.result.status, 1);
  assert.match(missing.report.failures.join(" "), /intake trade membership differs/);
  const stuck = run(false, false, false, true);
  assert.equal(stuck.result.status, 1);
  assert.match(stuck.report.failures.join(" "), /execution frontier missing/);
  assert.equal(stuck.calls.filter((kind) => kind === "counts").length, 1);
  assert.equal(stuck.calls.filter((kind) => kind === "membership").length, 0);
});

test("missing or stale transition and malformed ledger fail", () => {
  const incomplete = new Map(stages);
  incomplete.delete("execution:1");
  const result = assess(source, { stages: incomplete,
    metrics: { ...metrics, pending: "1", completions: "1", ledgerEntries: "3",
      badLedgerLegs: "1", badAttemptOutcomes: "1" } },
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

test("trade membership checks source, intake, and obligation identity", () => {
  const same = { sha256: "abc", bytes: 42 };
  assert.deepEqual(compareTradeMembership(same, same, same), []);
  assert.match(compareTradeMembership(same, { sha256: "other", bytes: 42 }, same)[0], /canonical source/);
  assert.match(compareTradeMembership(same, same, { sha256: "abc", bytes: 43 })[0], /obligation/);
});

test("PostgreSQL settlement proof rejects wrong ledger legs and attempt outcomes", {
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
    sql(`CREATE SCHEMA runtime;
      CREATE TABLE runtime.canonical_command_outcomes (
        event_stream text, partition_id integer, stream_sequence bigint,
        result_status text, result_payload jsonb);
      INSERT INTO runtime.canonical_command_outcomes VALUES
        ('test', 0, 1, 'accepted',
         '{"trades":[{"tradeId":"t1"},{"tradeId":"t2"}]}'::jsonb),
        ('test', 0, 2, 'accepted', '{"trades":[]}'::jsonb);`);
    assert.equal(sql(sourceTradeMembershipSql("test", [0])), "0|1|3|t1\n0|1|6|t2");
    sql(`CREATE SCHEMA settlement;
      CREATE TABLE settlement.canonical_trade_intake (
        trade_id text, event_stream text, source_generation text);
      INSERT INTO settlement.canonical_trade_intake VALUES ('t1', 'test', 'g1');
      CREATE TABLE settlement.canonical_transition_admission_completions (
        admission_rank bigint, event_stream text, source_generation text);
      INSERT INTO settlement.canonical_transition_admission_completions VALUES (1, 'test', 'g1');
      CREATE TABLE settlement.canonical_transition_admissions (
        event_stream text, source_generation text, admission_rank bigint);
      CREATE TABLE settlement.canonical_transition_dependencies (
        event_stream text, source_generation text, admission_rank bigint, predecessor_rank bigint);
      INSERT INTO settlement.canonical_transition_admissions VALUES
        ('test', 'g1', 1), ('test', 'g1', 2), ('test', 'g1', 3);
      INSERT INTO settlement.canonical_transition_dependencies VALUES
        ('test', 'g1', 2, 1), ('test', 'g1', 3, 2);
      CREATE TABLE settlement.canonical_settlement_obligations (
        event_stream text, source_generation text, trade_id text, run_id text, status text,
        buyer_participant_id text, buyer_account_id text, seller_participant_id text,
        seller_account_id text, currency text, instrument_id text,
        cash_amount numeric, quantity_units numeric,
        post_trade_mode text DEFAULT 'instant-post-trade',
        post_trade_profile_id text DEFAULT 'instant',
        post_trade_policy_version integer DEFAULT 1, partition_id integer DEFAULT 0,
        stream_sequence bigint DEFAULT 1);
      CREATE TABLE settlement.canonical_transition_attempts (
        event_stream text, source_generation text, trade_id text, attempt_number integer,
        outcome text, run_id text, post_trade_profile_id text, post_trade_policy_version integer,
        partition_id integer, stream_sequence bigint);
      CREATE TABLE settlement.canonical_transition_ledger_entries (
        event_stream text, source_generation text, trade_id text, attempt_number integer,
        entry_kind text, run_id text, participant_id text, account_id text,
        asset_type text, asset_id text, direction text, quantity numeric);
      INSERT INTO settlement.canonical_settlement_obligations
        (event_stream, source_generation, trade_id, run_id, status,
         buyer_participant_id, buyer_account_id, seller_participant_id, seller_account_id,
         currency, instrument_id, cash_amount, quantity_units) VALUES
        ('test', 'g1', 't1', 'r1', 'SETTLED', 'buyer', 'b1', 'seller', 's1', 'USD', 'ABC', 250, 5);
      INSERT INTO settlement.canonical_transition_attempts VALUES
        ('test', 'g1', 't1', 1, 'SETTLED', 'r1', 'instant', 1, 0, 1);
      INSERT INTO settlement.canonical_transition_ledger_entries VALUES
        ('test', 'g1', 't1', 1, 'BUYER_CASH_DEBIT', 'r1', 'buyer', 'b1', 'CASH', 'USD', 'DEBIT', 250),
        ('test', 'g1', 't1', 1, 'SELLER_CASH_CREDIT', 'r1', 'seller', 's1', 'CASH', 'USD', 'CREDIT', 250),
        ('test', 'g1', 't1', 1, 'SELLER_SECURITY_DEBIT', 'r1', 'seller', 's1', 'SECURITY', 'ABC', 'DEBIT', 5),
        ('test', 'g1', 't1', 1, 'BUYER_SECURITY_CREDIT', 'r1', 'buyer', 'b1', 'SECURITY', 'ABC', 'CREDIT', 5);`);
    assert.deepEqual(sql(settlementCountsSql("test", "g1")).split("|"),
      ["1", "1", "0", "1", "0", "0", "3", "3", "1", "1", "4"]);
    const edges = sql(dependencyGraphSql("test", "g1")).split("\n").map((row) => row.split("|"));
    assert.deepEqual(assessDependencyGraph(edges),
      { admissions: 3, edges: 2, maxDepth: 3, deepestAdmissionRank: "3" });
    const stageStats = sql(settlementStageSql()).split("|");
    assert.equal(stageStats.length, 9);
    assert.equal(stageStats[8], "6");
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
    const checkAttempt = () => sql(attemptMismatchSql("test", "g1"));
    assert.equal(checkAttempt(), "0");
    sql("UPDATE settlement.canonical_transition_attempts SET outcome = 'BREAK'");
    assert.equal(checkAttempt(), "1", "wrong attempt outcome must fail");
    sql("DELETE FROM settlement.canonical_transition_attempts");
    assert.equal(checkAttempt(), "1", "missing attempt must fail");
  } finally {
    spawnSync("docker", ["rm", "-f", name], { encoding: "utf8" });
  }
});
