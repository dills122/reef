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
    const targetDuty = (sample.settlementQueryMs ?? 0) / (settlementSeconds * 1000);
    const duringLoad = loadWindows.some(({ startedAt, finishedAt }) =>
      Date.parse(before.sourceMeasuredAt ?? before.sampledAt) >= Date.parse(startedAt) &&
      Date.parse(sample.sourceMeasuredAt ?? sample.sampledAt) <= Date.parse(finishedAt) &&
      (!requireSettlement ||
        Date.parse(before.settlementMeasuredAt ?? before.sampledAt) >= Date.parse(startedAt) &&
        Date.parse(sample.settlementMeasuredAt ?? sample.sampledAt) <= Date.parse(finishedAt)));
    if (duringLoad) {
      maxObserverDutyCycle = Math.max(maxObserverDutyCycle, duty);
      maxTargetObserverDutyCycle = Math.max(maxTargetObserverDutyCycle, targetDuty);
      if (duty > 0.10) failures.push(`sample ${index} observer query duty cycle exceeds 10%`);
      if (requireSettlement && targetDuty > 0.02) {
        failures.push(`sample ${index} settlement observer query duty cycle exceeds 2%`);
      }
    }
    if (sample.sourceOutcomes == null || before.sourceOutcomes == null) {
      failures.push(`sample ${index} lacks source outcome count`);
      return;
    }
    const row = { sampledAt: sample.sampledAt, sourceSeconds, settlementSeconds, duringLoad,
      observerDutyCycle: duty, targetObserverDutyCycle: targetDuty,
      sourceOutcomesPerSecond: Number(BigInt(sample.sourceOutcomes) - BigInt(before.sourceOutcomes)) / sourceSeconds };
    const hasSourceTrades = sample.sourceTrades != null && before.sourceTrades != null;
    if ((sample.sourceTrades != null) !== (before.sourceTrades != null)) {
      failures.push(`sample ${index} source trade count is inconsistent`);
    }
    if (hasSourceTrades) {
      row.sourceTradesPerSecond = Number(BigInt(sample.sourceTrades) - BigInt(before.sourceTrades)) / sourceSeconds;
    }
    if (row.sourceOutcomesPerSecond < 0 || (hasSourceTrades && row.sourceTradesPerSecond < 0)) {
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

/** Candidate measurement gate; exact cohort and read-latency gates run separately. */
export function assessJournalStageSamples(samples, loadWindows = []) {
  const failures = [];
  const rates = [];
  if (samples.length < 3) failures.push("fewer than three journal stage samples");
  const numeric = (sample, path, index) => {
    const value = path.split(".").reduce((current, key) => current?.[key], sample);
    if (value == null || !/^[0-9]+$/.test(String(value))) {
      failures.push(`sample ${index} lacks ${path}`);
      return null;
    }
    return BigInt(value);
  };
  let maxObserverDutyCycle = 0;
  samples.forEach((sample, index) => {
    if (sample.error) failures.push(`sample ${index} failed: ${sample.error}`);
    const paths = ["sourceOutcomes", "sourceFrontierOffsetsTotal", "journal.batchesInserted",
      "journal.resultsInserted", "journal.headSequence", "journal.financialProjectionSequence",
      "journal.sourceFrontierOffsetsTotal", "marketCandidate.partitionFrontiers",
      "marketCandidate.frontierOffsetsTotal", "marketCandidate.windowsInserted",
      "marketCandidate.tapeRowsInserted"];
    const values = Object.fromEntries(paths.map((path) => [path, numeric(sample, path, index)]));
    if (Object.values(values).some((value) => value == null)) return;
    const source = values.sourceFrontierOffsetsTotal;
    const journal = values["journal.sourceFrontierOffsetsTotal"];
    const market = values["marketCandidate.frontierOffsetsTotal"];
    const head = values["journal.headSequence"];
    const financial = values["journal.financialProjectionSequence"];
    if (journal > source || market > source || financial > head) {
      failures.push(`sample ${index} candidate frontier exceeds its authority`);
    }
    if (index === 0) return;
    const before = samples[index - 1];
    if (before.error) return;
    const seconds = (Date.parse(sample.sampledAt) - Date.parse(before.sampledAt)) / 1000;
    if (!Number.isFinite(seconds) || seconds <= 0) {
      failures.push(`sample ${index} time did not advance`);
      return;
    }
    const previous = Object.fromEntries(paths.map((path) => [path,
      path.split(".").reduce((current, key) => current?.[key], before)]));
    if (Object.values(previous).some((value) => value == null)) return;
    for (const path of paths) {
      if (values[path] < BigInt(previous[path])) failures.push(`sample ${index} ${path} regressed`);
    }
    const duty = ((sample.sourceQueryMs ?? 0) + (sample.sourceFrontierQueryMs ?? 0) +
      (sample.journalQueryMs ?? 0) + (sample.marketQueryMs ?? 0)) / (seconds * 1000);
    const duringLoad = loadWindows.some(({ startedAt, finishedAt }) =>
      Date.parse(before.sampledAt) >= Date.parse(startedAt) &&
      Date.parse(sample.sampledAt) <= Date.parse(finishedAt));
    if (duringLoad) {
      if (values["marketCandidate.partitionFrontiers"] !== 16n) {
        failures.push(`sample ${index} has fewer than 16 market partition frontiers during load`);
      }
      maxObserverDutyCycle = Math.max(maxObserverDutyCycle, duty);
      if (duty > 0.10) failures.push(`sample ${index} observer query duty cycle exceeds 10%`);
    }
    rates.push({ sampledAt: sample.sampledAt, duringLoad, observerDutyCycle: duty,
      sourceOutcomesPerSecond: Number(values.sourceOutcomes - BigInt(previous.sourceOutcomes)) / seconds,
      sourceFrontierPerSecond: Number(source - BigInt(previous.sourceFrontierOffsetsTotal)) / seconds,
      journalBatchesPerSecond: Number(head - BigInt(previous["journal.headSequence"])) / seconds,
      journalResultsPerSecond: Number(values["journal.resultsInserted"] -
        BigInt(previous["journal.resultsInserted"])) / seconds,
      marketTapeRowsPerSecond: Number(values["marketCandidate.tapeRowsInserted"] -
        BigInt(previous["marketCandidate.tapeRowsInserted"])) / seconds,
      sourceToJournalGap: (source - journal).toString(),
      sourceToMarketGap: (source - market).toString(),
      journalToFinancialGap: (head - financial).toString() });
  });
  const inLoad = rates.filter((rate) => rate.duringLoad);
  if (loadWindows.length && inLoad.length < 3) failures.push("fewer than three in-load journal intervals");
  for (const { finishedAt } of loadWindows) {
    const last = inLoad.filter((rate) => Date.parse(rate.sampledAt) <= Date.parse(finishedAt))
      .at(-1);
    if (!last || Date.parse(finishedAt) - Date.parse(last.sampledAt) > 15_000) {
      failures.push("no journal stage sample within 15 seconds of measured load end");
    }
  }
  const backlogLimits = {};
  if (loadWindows.length && inLoad.length >= 3) {
    const sourceRate = inLoad.reduce((sum, row) => sum + row.sourceFrontierPerSecond, 0) / inLoad.length;
    const journalRate = inLoad.reduce((sum, row) => sum + row.journalBatchesPerSecond, 0) / inLoad.length;
    for (const [name, field, rate, jitter] of [
      ["source-to-journal", "sourceToJournalGap", sourceRate, 640],
      ["source-to-market", "sourceToMarketGap", sourceRate, 640],
      ["journal-to-financial", "journalToFinancialGap", journalRate, 1],
    ]) {
      if (!(rate > 0)) {
        failures.push(`${name} authority made no in-load progress`);
        continue;
      }
      const gaps = inLoad.map((row) => BigInt(row[field]));
      const growthAllowance = BigInt(Math.ceil(Math.max(jitter, rate * 2)));
      const absoluteLimit = BigInt(Math.ceil(Math.max(jitter, rate * 5)));
      backlogLimits[name] = { growthAllowance: growthAllowance.toString(),
        absoluteLimit: absoluteLimit.toString(), ratePerSecond: rate };
      if (gaps.at(-1) > gaps[0] + growthAllowance) {
        failures.push(`${name} backlog grew more than two seconds of in-load authority progress`);
      }
      if (gaps.some((gap) => gap > absoluteLimit)) {
        failures.push(`${name} backlog exceeded five seconds of in-load authority progress`);
      }
      const tail = gaps.slice(-3);
      if (tail[1] > tail[0] + BigInt(jitter) &&
          tail[2] > tail[1] + BigInt(jitter)) {
        failures.push(`${name} backlog grew across the final two in-load intervals`);
      }
    }
  }
  return { status: failures.length ? "fail" : "pass", scope: "stage-measurement-only",
    failures, sampleCount: samples.length, inLoadIntervalCount: inLoad.length,
    maxObserverDutyCycle, backlogLimits, rates };
}

function main() {
  const [input, output, settlementText] = process.argv.slice(2);
  if (!input || !output || !["true", "false", "journal"].includes(settlementText)) {
    throw new Error("usage: postmatch-stage-check.mjs INPUT_JSONL OUTPUT_JSON true|false|journal");
  }
  const samples = readFileSync(input, "utf8").trim().split("\n").filter(Boolean).map((line) => JSON.parse(line));
  const loadWindows = readdirSync(dirname(input))
    .filter((name) => /^venue-event-materializer-stress-rate-.*\.json$/.test(name))
    .map((name) => {
      const report = JSON.parse(readFileSync(join(dirname(input), name), "utf8"));
      return { report: name, startedAt: report.startedAt, finishedAt: report.finishedAt };
    });
  const result = settlementText === "journal" ? assessJournalStageSamples(samples, loadWindows) :
    assessStageSamples(samples, settlementText === "true", loadWindows);
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
