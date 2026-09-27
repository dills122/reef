import assert from "node:assert/strict";
import test from "node:test";

import { assessStageSamples } from "./postmatch-stage-check.mjs";

const settlement = (value) => ({ intakeTrades: String(value), obligations: String(value),
  admissions: String(value), completions: String(value), attempts: String(value),
  ledgerEntries: String(value * 4), lockWaitSessions: "0", transactionIdWaitSessions: "0" });
const sample = (time, source, stage, queryMs = 10) => ({ sampledAt: new Date(time).toISOString(),
  sourceOutcomes: String(source), sourceTrades: String(source / 2),
  sourceQueryMs: queryMs, settlementQueryMs: queryMs, settlement: settlement(stage) });

test("stage check derives comparable in-load rates", () => {
  const report = assessStageSamples([sample(0, 0, 0), sample(10000, 100, 50),
    sample(20000, 200, 100)], true);
  assert.equal(report.status, "pass");
  assert.equal(report.rates[1].sourceTradesPerSecond, 5);
  assert.equal(report.rates[1].attemptsPerSecond, 5);
});

test("stage check fails missing target stages, regression, and intrusive observer", () => {
  const samples = [sample(0, 0, 0), sample(10000, 100, 50), sample(20000, 90, 40, 3000)];
  delete samples[1].settlement;
  const report = assessStageSamples(samples, true);
  assert.equal(report.status, "fail");
  assert.ok(report.failures.some((failure) => failure.includes("lacks settlement")));
  assert.ok(report.failures.some((failure) => failure.includes("regressed")));
  assert.ok(report.failures.some((failure) => failure.includes("duty cycle")));
});

test("stage check requires three intervals entirely inside measured load window", () => {
  const samples = [0, 10000, 20000, 30000, 40000].map((time, index) =>
    sample(time, index * 100, index * 50));
  const narrow = assessStageSamples(samples, true,
    [{ startedAt: new Date(5000).toISOString(), finishedAt: new Date(35000).toISOString() }]);
  assert.equal(narrow.status, "fail");
  assert.equal(narrow.inLoadIntervalCount, 2);
  const full = assessStageSamples(samples, true,
    [{ startedAt: new Date(0).toISOString(), finishedAt: new Date(40000).toISOString() }]);
  assert.equal(full.status, "pass");
  assert.equal(full.inLoadIntervalCount, 4);
});

test("stage check reports sampler error without trying to derive missing rates", () => {
  const report = assessStageSamples([sample(0, 0, 0),
    { sampledAt: new Date(10000).toISOString(), error: "database timeout" },
    sample(20000, 100, 50)], true);
  assert.equal(report.status, "fail");
  assert.ok(report.failures.some((failure) => failure.includes("database timeout")));
});

test("source and settlement rates use their own observation timestamps", () => {
  const samples = [sample(0, 0, 0), sample(10000, 100, 50), sample(20000, 200, 100)];
  samples[0].sourceMeasuredAt = new Date(1000).toISOString();
  samples[1].sourceMeasuredAt = new Date(11000).toISOString();
  samples[2].sourceMeasuredAt = new Date(21000).toISOString();
  samples[0].settlementMeasuredAt = new Date(2000).toISOString();
  samples[1].settlementMeasuredAt = new Date(14000).toISOString();
  samples[2].settlementMeasuredAt = new Date(26000).toISOString();
  const report = assessStageSamples(samples, true);
  assert.equal(report.status, "pass");
  assert.equal(report.rates[0].sourceTradesPerSecond, 5);
  assert.equal(report.rates[0].attemptsPerSecond, 50 / 12);
});
