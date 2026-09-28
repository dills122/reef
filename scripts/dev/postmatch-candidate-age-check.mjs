#!/usr/bin/env node
import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { composeArgs } from "./lib/compose-utils.mjs";

const DEFAULT_LIMITS = { p95Ms: 5000, p99Ms: 10000, maxMs: 30000 };

export function assessCandidateVisibleAge(lines, eventStream, startedAt, limits = DEFAULT_LIMITS,
  expectedWindows = null, expectedPartitionCount = null) {
  const failures = [];
  const started = Date.parse(startedAt);
  if (!eventStream || !Number.isFinite(started)) failures.push("valid event stream and load start are required");
  const windows = [];
  const allReceipts = new Map();
  for (const line of lines) {
    const marker = line.indexOf("postmatch_candidate_visible_age ");
    if (marker < 0) continue;
    const fields = Object.fromEntries(line.slice(marker + "postmatch_candidate_visible_age ".length)
      .trim().split(/\s+/).map((entry) => entry.split("=", 2)));
    if (fields.event_stream !== eventStream) continue;
    const observedAt = Date.parse(fields.observed_at);
    const partition = Number(fields.partition);
    const outcomes = Number(fields.outcomes);
    const ageMs = Number(fields.source_to_visible_upper_bound_ms);
    const from = fields.from_exclusive;
    const through = fields.through;
    if (!Number.isFinite(observedAt) || !Number.isInteger(partition) || partition < 0 ||
        !Number.isSafeInteger(outcomes) || outcomes < 1 || !Number.isSafeInteger(ageMs) || ageMs < 0 ||
        !/^\d+$/.test(from ?? "") || !/^\d+$/.test(through ?? "") ||
        BigInt(through) <= BigInt(from)) {
      failures.push("malformed candidate visible-age receipt");
      continue;
    }
    const key = `${partition}/${from}/${through}`;
    allReceipts.set(key, (allReceipts.get(key) ?? 0) + 1);
    if (observedAt >= started) windows.push({ observedAt, partition, outcomes, ageMs });
  }
  if (windows.length === 0) failures.push("no candidate visible-age receipts after load start");
  if (expectedWindows != null) {
    const expected = new Set(expectedWindows.map(({ partition, from, through }) =>
      `${partition}/${from}/${through}`));
    if (expected.size !== expectedWindows.length || expected.size === 0) {
      failures.push("projected market window evidence is empty or duplicated");
    }
    for (const key of expected) if (allReceipts.get(key) !== 1) {
      failures.push(`projected market window lacks one visible-age receipt: ${key}`);
    }
    for (const [key, count] of allReceipts) if (!expected.has(key) || count !== 1) {
      failures.push(`visible-age receipt has no unique projected window: ${key}`);
    }
    if (expectedPartitionCount != null &&
        new Set(expectedWindows.map((window) => window.partition)).size !== expectedPartitionCount) {
      failures.push("projected market windows do not cover every benchmark partition");
    }
  }
  const sorted = [...windows].sort((left, right) => left.ageMs - right.ageMs);
  const outcomeCount = sorted.reduce((total, row) => total + row.outcomes, 0);
  function percentile(percent) {
    const target = Math.ceil(outcomeCount * percent);
    let seen = 0;
    for (const row of sorted) {
      seen += row.outcomes;
      if (seen >= target) return row.ageMs;
    }
    return null;
  }
  const p95Ms = outcomeCount ? percentile(0.95) : null;
  const p99Ms = outcomeCount ? percentile(0.99) : null;
  const maxMs = sorted.at(-1)?.ageMs ?? null;
  if (p95Ms != null && p95Ms > limits.p95Ms) failures.push(`source-to-visible p95 upper bound ${p95Ms}ms exceeds ${limits.p95Ms}ms`);
  if (p99Ms != null && p99Ms > limits.p99Ms) failures.push(`source-to-visible p99 upper bound ${p99Ms}ms exceeds ${limits.p99Ms}ms`);
  if (maxMs != null && maxMs > limits.maxMs) failures.push(`source-to-visible max upper bound ${maxMs}ms exceeds ${limits.maxMs}ms`);
  return { status: failures.length ? "fail" : "pass", method: "POST_COMMIT_CONSERVATIVE_UPPER_BOUND",
    eventStream, windowCount: windows.length, outcomeCount,
    partitions: [...new Set(windows.map((row) => row.partition))].sort((a, b) => a - b),
    p95Ms, p99Ms, maxMs, limits, expectedWindowCount: expectedWindows?.length ?? null,
    failures };
}

function projectedWindows(eventStream) {
  if (!/^[A-Za-z0-9_-]+$/.test(eventStream)) throw new Error("invalid candidate event stream");
  const sql = `SELECT partition_id,from_exclusive_sequence,through_inclusive_sequence,
    projector_generation FROM postmatch.matching_market_candidate_windows
    WHERE event_stream='${eventStream}' ORDER BY partition_id,from_exclusive_sequence`;
  const result = spawnSync("docker", composeArgs(["exec", "-T", "postmatch-postgres", "psql",
    "-X", "-A", "-t", "-F", "|", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef",
    "-c", sql]), { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(result.stderr?.trim() || "market window query failed");
  const rows = result.stdout.trim().split("\n").filter(Boolean).map((line) => line.split("|"));
  const generations = new Set(rows.map((row) => row[3]));
  if (generations.size !== 1 || rows.some((row) => row.length !== 4)) {
    throw new Error("market window generation is missing or ambiguous");
  }
  return rows.map(([partition, from, through]) => ({ partition: Number(partition), from, through }));
}

if (process.argv[1]?.endsWith("postmatch-candidate-age-check.mjs")) {
  const [logPath, loadReportPath, eventStream, outputPath, partitionCountText] = process.argv.slice(2);
  if (!logPath || !loadReportPath || !eventStream || !outputPath) {
    throw new Error("usage: postmatch-candidate-age-check.mjs LOG LOAD_REPORT EVENT_STREAM OUTPUT");
  }
  const report = JSON.parse(readFileSync(loadReportPath, "utf8"));
  const partitionCount = Number(partitionCountText);
  if (!Number.isInteger(partitionCount) || partitionCount < 1 || partitionCount > 32) {
    throw new Error("candidate visible-age check requires partition count 1..32");
  }
  const result = assessCandidateVisibleAge(readFileSync(logPath, "utf8").split("\n"), eventStream,
    report.startedAt, DEFAULT_LIMITS, projectedWindows(eventStream), partitionCount);
  writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`);
  console.log(`candidate source-to-visible upper-bound gate: ${result.status}; outcomes=${result.outcomeCount}`);
  for (const failure of result.failures) console.error(failure);
  if (result.status !== "pass") process.exitCode = 1;
}
