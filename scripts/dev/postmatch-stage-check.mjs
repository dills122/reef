#!/usr/bin/env node
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

const stageKeys = ["intakeTrades", "obligations", "admissions", "completions", "attempts", "ledgerEntries"];

export function assessStageSamples(samples, requireSettlement, loadWindows = []) {
  const failures = [];
  if (samples.length < 3) failures.push("fewer than three in-load stage samples");
  const rates = [];
  let maxObserverDutyCycle = 0;
  let maxTargetObserverDutyCycle = 0;
  samples.forEach((sample, index) => {
    if (sample.error) failures.push(`sample ${index} failed: ${sample.error}`);
    if (requireSettlement && !sample.settlement) failures.push(`sample ${index} lacks settlement stages`);
    if (index === 0) return;
    const before = samples[index - 1];
    if (sample.error || before.error) return;
    const sourceSeconds = (Date.parse(sample.sourceMeasuredAt ?? sample.sampledAt) -
      Date.parse(before.sourceMeasuredAt ?? before.sampledAt)) / 1000;
    const settlementSeconds = (Date.parse(sample.settlementMeasuredAt ?? sample.sampledAt) -
      Date.parse(before.settlementMeasuredAt ?? before.sampledAt)) / 1000;
    if (!Number.isFinite(sourceSeconds) || sourceSeconds <= 0 ||
        requireSettlement && (!Number.isFinite(settlementSeconds) || settlementSeconds <= 0)) {
      failures.push(`sample ${index} time did not advance`);
      return;
    }
    const duty = ((sample.sourceQueryMs ?? 0) + (sample.settlementQueryMs ?? 0)) / (sourceSeconds * 1000);
    maxObserverDutyCycle = Math.max(maxObserverDutyCycle, duty);
    if (duty > 0.10) failures.push(`sample ${index} observer query duty cycle exceeds 10%`);
    const targetDuty = (sample.settlementQueryMs ?? 0) / (settlementSeconds * 1000);
    maxTargetObserverDutyCycle = Math.max(maxTargetObserverDutyCycle, targetDuty);
    if (requireSettlement && targetDuty > 0.02) {
      failures.push(`sample ${index} settlement observer query duty cycle exceeds 2%`);
    }
    const duringLoad = loadWindows.some(({ startedAt, finishedAt }) =>
      Date.parse(before.sourceMeasuredAt ?? before.sampledAt) >= Date.parse(startedAt) &&
      Date.parse(sample.sourceMeasuredAt ?? sample.sampledAt) <= Date.parse(finishedAt) &&
      (!requireSettlement ||
        Date.parse(before.settlementMeasuredAt ?? before.sampledAt) >= Date.parse(startedAt) &&
        Date.parse(sample.settlementMeasuredAt ?? sample.sampledAt) <= Date.parse(finishedAt)));
    const row = { sampledAt: sample.sampledAt, sourceSeconds, settlementSeconds, duringLoad,
      sourceOutcomesPerSecond: Number(BigInt(sample.sourceOutcomes) - BigInt(before.sourceOutcomes)) / sourceSeconds,
      sourceTradesPerSecond: Number(BigInt(sample.sourceTrades) - BigInt(before.sourceTrades)) / sourceSeconds };
    if (row.sourceOutcomesPerSecond < 0 || row.sourceTradesPerSecond < 0) {
      failures.push(`sample ${index} source count regressed`);
    }
    if (requireSettlement && sample.settlement && before.settlement) {
      for (const key of stageKeys) {
        const delta = BigInt(sample.settlement[key]) - BigInt(before.settlement[key]);
        if (delta < 0) failures.push(`sample ${index} settlement ${key} regressed`);
        row[`${key}PerSecond`] = Number(delta) / settlementSeconds;
      }
      row.lockWaitSessions = Number(sample.settlement.lockWaitSessions);
      row.transactionIdWaitSessions = Number(sample.settlement.transactionIdWaitSessions);
    }
    rates.push(row);
  });
  const inLoadRates = rates.filter((row) => row.duringLoad);
  if (loadWindows.length && inLoadRates.length < 3) failures.push("fewer than three in-load rate intervals");
  return { status: failures.length ? "fail" : "pass", failures, sampleCount: samples.length,
    inLoadIntervalCount: inLoadRates.length, maxObserverDutyCycle,
    maxTargetObserverDutyCycle, rates };
}

function main() {
  const [input, output, settlementText] = process.argv.slice(2);
  if (!input || !output || !["true", "false"].includes(settlementText)) {
    throw new Error("usage: postmatch-stage-check.mjs INPUT_JSONL OUTPUT_JSON true|false");
  }
  const samples = readFileSync(input, "utf8").trim().split("\n").filter(Boolean).map((line) => JSON.parse(line));
  const loadWindows = readdirSync(dirname(input))
    .filter((name) => /^venue-event-materializer-stress-rate-.*\.json$/.test(name))
    .map((name) => {
      const report = JSON.parse(readFileSync(join(dirname(input), name), "utf8"));
      return { report: name, startedAt: report.startedAt, finishedAt: report.finishedAt };
    });
  const result = assessStageSamples(samples, settlementText === "true", loadWindows);
  result.loadWindows = loadWindows;
  if (loadWindows.length === 0) {
    result.failures.push("no measured load report for stage alignment");
    result.status = "fail";
  }
  writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`);
  console.log(`post-match in-load stage samples: ${result.status}; samples=${samples.length}; report=${output}`);
  for (const failure of result.failures) console.error(failure);
  if (result.status !== "pass") process.exitCode = 1;
}

if (process.argv[1]?.endsWith("postmatch-stage-check.mjs")) main();
