import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);
const config = {
  runId: stringOption("--run-id", `arena-local-${Date.now()}`),
  modeId: stringOption("--mode-id", "phase-1-local"),
  scoringPolicyVersion: stringOption("--scoring-policy-version", "score-v0"),
  runnerWorkers: numberOption("--workers", 2),
  bots: csvOption("--bots", "simple,lifecycle,refreshing,multi-symbol"),
  iterations: numberOption("--iterations", 10),
  concurrency: numberOption("--concurrency", 4),
  compartment: stringOption("--compartment", "ses"),
  maxScenarioP95Ms: numberOption("--max-scenario-p95-ms", 50),
  maxFailedRuns: numberOption("--max-failed-runs", 0),
  maxIssueCount: numberOption("--max-issue-count", 0),
  out: stringOption("--out", "/tmp/reef-arena-local-run.json"),
};

const runnerReportPath = config.out.replace(/\.json$/, ".runner.json");
runPoolSmoke(runnerReportPath);
const runnerReport = JSON.parse(readFileSync(runnerReportPath, "utf8"));
const botResults = scoreBots(runnerReport);
const enforcementEvents = enforce(botResults, runnerReport);
const report = {
  schemaVersion: "reef.arena.localRun.v0",
  generatedAt: new Date().toISOString(),
  runId: config.runId,
  modeId: config.modeId,
  scoringPolicyVersion: config.scoringPolicyVersion,
  runnerProfile: {
    workers: config.runnerWorkers,
    compartment: config.compartment,
    bots: config.bots,
    iterations: config.iterations,
    concurrency: config.concurrency,
  },
  status: enforcementEvents.some((event) => event.decision === "freeze") ? "completed_with_freezes" : "completed",
  thresholds: {
    maxScenarioP95Ms: config.maxScenarioP95Ms,
    maxFailedRuns: config.maxFailedRuns,
    maxIssueCount: config.maxIssueCount,
  },
  runnerReportPath,
  runnerSummary: {
    loadElapsedMs: runnerReport.loadElapsedMs,
    runElapsedMs: runnerReport.runElapsedMs,
    totals: runnerReport.totals,
    latencyMs: runnerReport.latencyMs,
    workerLatencyMs: runnerReport.workerLatencyMs,
  },
  enforcementEvents,
  botResults,
  leaderboard: botResults
    .slice()
    .sort((left, right) =>
      Number(left.disqualified) - Number(right.disqualified) ||
      right.finalEquity - left.finalEquity ||
      right.realizedPnl - left.realizedPnl ||
      left.maxDrawdown - right.maxDrawdown ||
      left.botId.localeCompare(right.botId),
    )
    .map((result, index) => ({ rank: index + 1, ...result })),
};

mkdirSync(dirname(config.out), { recursive: true });
writeFileSync(config.out, `${JSON.stringify(report, null, 2)}\n`);
console.log(`arena local run complete: ${resolve(config.out)}`);
console.log(
  `status=${report.status} bots=${botResults.length} freezes=${enforcementEvents.filter((event) => event.decision === "freeze").length} runnerP95=${runnerReport.latencyMs.p95.toFixed(2)}ms`,
);
for (const entry of report.leaderboard) {
  console.log(
    `rank=${entry.rank} bot=${entry.botId} finalEquity=${entry.finalEquity} disqualified=${entry.disqualified} freezes=${entry.freezeCount}`,
  );
}

function runPoolSmoke(outPath) {
  const command = [
    "scripts/dev/arena-runner-pool-smoke.mjs",
    `--workers=${config.runnerWorkers}`,
    `--bots=${config.bots.join(",")}`,
    `--iterations=${config.iterations}`,
    `--concurrency=${config.concurrency}`,
    `--compartment=${config.compartment}`,
    `--out=${outPath}`,
  ];
  const result = spawnSync("bun", command, { cwd: repoRoot, encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(`arena runner pool smoke failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`);
  }
  return result;
}

function scoreBots(runnerReport) {
  const grouped = new Map();
  for (const run of runnerReport.runReports ?? []) {
    const current = grouped.get(run.caseId) ?? {
      botId: run.caseId,
      versionId: "local-fixture",
      runs: 0,
      failedRuns: 0,
      ticksRun: 0,
      actionsProposed: 0,
      orderActionsProposed: 0,
      dataCalls: 0,
      venueCommands: 0,
      issueCount: 0,
      latencySamplesMs: [],
    };
    current.runs += 1;
    current.failedRuns += run.ok ? 0 : 1;
    current.ticksRun += Number(run.summary?.ticksRun ?? 0);
    current.actionsProposed += Number(run.summary?.actionsProposed ?? 0);
    current.orderActionsProposed += Number(run.summary?.orderActionsProposed ?? 0);
    current.dataCalls += Number(run.summary?.dataCalls ?? 0);
    current.venueCommands += Number(run.summary?.venueCommands ?? 0);
    current.issueCount += Number(run.summary?.issues?.length ?? 0);
    current.latencySamplesMs.push(Number(run.workerElapsedMs ?? run.elapsedMs ?? 0));
    grouped.set(run.caseId, current);
  }

  return Array.from(grouped.values()).map((bot) => {
    const sorted = bot.latencySamplesMs.slice().sort((a, b) => a - b);
    const { latencySamplesMs, ...botSummary } = bot;
    const p95 = percentile(sorted, 0.95);
    const failedRunPenalty = bot.failedRuns * 100_000;
    const issuePenalty = bot.issueCount * 25_000;
    const latencyPenalty = Math.max(0, Math.round((p95 - config.maxScenarioP95Ms) * 1_000));
    const activityScore = bot.orderActionsProposed * 100 + bot.venueCommands * 25 + bot.dataCalls * 5;
    const finalEquity = 1_000_000 + activityScore - failedRunPenalty - issuePenalty - latencyPenalty;
    const realizedPnl = finalEquity - 1_000_000;
    const maxDrawdown = Math.max(0, failedRunPenalty + issuePenalty + latencyPenalty);
    return {
      ...botSummary,
      latencyP95Ms: p95,
      finalEquity,
      realizedPnl,
      maxDrawdown,
      disqualified: false,
      freezeCount: 0,
    };
  });
}

function enforce(botResults, runnerReport) {
  const events = [];
  for (const result of botResults) {
    const reasons = [];
    if (result.failedRuns > config.maxFailedRuns) {
      reasons.push(`failedRuns ${result.failedRuns} > ${config.maxFailedRuns}`);
    }
    if (result.issueCount > config.maxIssueCount) {
      reasons.push(`issueCount ${result.issueCount} > ${config.maxIssueCount}`);
    }
    if (result.latencyP95Ms > config.maxScenarioP95Ms) {
      reasons.push(`latencyP95Ms ${result.latencyP95Ms.toFixed(2)} > ${config.maxScenarioP95Ms}`);
    }
    if (reasons.length > 0) {
      result.disqualified = true;
      result.freezeCount = 1;
      events.push({
        type: "arena.enforcement.v0",
        runId: config.runId,
        botId: result.botId,
        versionId: result.versionId,
        decision: "freeze",
        reason: reasons.join("; "),
        policyVersion: "arena-local-enforcement-v0",
        counters: {
          failedRuns: result.failedRuns,
          issueCount: result.issueCount,
          latencyP95Ms: result.latencyP95Ms,
          runnerP95Ms: runnerReport.latencyMs?.p95 ?? 0,
        },
        occurredAt: new Date().toISOString(),
      });
    }
  }
  return events;
}

function percentile(sortedValues, pct) {
  if (sortedValues.length === 0) return 0;
  const index = Math.min(sortedValues.length - 1, Math.ceil(sortedValues.length * pct) - 1);
  return sortedValues[index];
}

function csvOption(name, fallback) {
  return stringOption(name, fallback).split(",").map((value) => value.trim()).filter(Boolean);
}

function numberOption(name, fallback) {
  const raw = optionValue(name);
  if (raw === undefined) return fallback;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be numeric; got ${raw}`);
  }
  return parsed;
}

function stringOption(name, fallback) {
  return optionValue(name) ?? fallback;
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}
