#!/usr/bin/env node
// Read-only rank DAG analysis after a stopped-source disposable run.
import { spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";

const compose = ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml", "--profile", "postmatch"];
const literal = (value) => `'${value.replaceAll("'", "''")}'`;

function query(service, sql) {
  const result = spawnSync("docker", [...compose, "exec", "-T", service, "psql", "-X", "-A", "-t",
    "-F", "\t", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef", "-c", sql],
  { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${service} dependency query failed: ${result.stderr?.trim() || result.error?.message}`);
  return result.stdout.trim() ? result.stdout.trim().split("\n").map((line) => line.split("\t")) : [];
}

export function assessDependencyGraph(rows) {
  const depths = new Map();
  let currentRank = null;
  let currentDepth = 1;
  let maxDepth = 0;
  let deepestRank = null;
  let edgeCount = 0;
  let seenPredecessors = new Set();
  function finish() {
    if (currentRank === null) return;
    depths.set(currentRank, currentDepth);
    if (currentDepth > maxDepth) { maxDepth = currentDepth; deepestRank = currentRank; }
  }
  for (const [rankText, predecessorText] of rows) {
    const rank = BigInt(rankText);
    if (rank <= 0n) throw new Error("nonpositive admission rank in dependency graph");
    if (currentRank !== rank) {
      if (currentRank !== null && rank <= currentRank) throw new Error("dependency graph ranks are unordered");
      finish();
      currentRank = rank;
      currentDepth = 1;
      seenPredecessors = new Set();
    }
    if (seenPredecessors.has(predecessorText) ||
        (seenPredecessors.size > 0 && (predecessorText === "" || seenPredecessors.has("")))) {
      throw new Error(`duplicate or inconsistent dependency row for admission rank ${rank}`);
    }
    seenPredecessors.add(predecessorText);
    if (predecessorText) {
      const predecessor = BigInt(predecessorText);
      if (predecessor >= rank || !depths.has(predecessor)) {
        throw new Error(`dependency predecessor missing or not earlier: ${predecessor} -> ${rank}`);
      }
      currentDepth = Math.max(currentDepth, depths.get(predecessor) + 1);
      edgeCount += 1;
    }
  }
  finish();
  return { admissions: depths.size, edges: edgeCount, maxDepth,
    deepestAdmissionRank: deepestRank?.toString() ?? null };
}

export function dependencyGraphSql(stream, generation) {
  const where = `a.event_stream = ${literal(stream)} AND a.source_generation = ${literal(generation)}`;
  return `SELECT a.admission_rank::text, coalesce(d.predecessor_rank::text, '')
    FROM settlement.canonical_transition_admissions a
    LEFT JOIN settlement.canonical_transition_dependencies d
      ON d.event_stream = a.event_stream AND d.source_generation = a.source_generation
     AND d.admission_rank = a.admission_rank
    WHERE ${where} ORDER BY a.admission_rank, d.predecessor_rank`;
}

function main() {
  const [stream, output] = process.argv.slice(2);
  if (!/^[A-Za-z0-9_]+$/.test(stream ?? "") || !output) {
    throw new Error("usage: settlement-dependency-graph-check.mjs EVENT_STREAM OUTPUT_JSON");
  }
  const generation = query("postgres",
    "SELECT generation::text FROM runtime.postmatch_source_generation WHERE singleton = TRUE");
  if (generation.length !== 1) throw new Error("source generation missing");
  const rows = query("settlement-postgres", dependencyGraphSql(stream, generation[0][0]));
  const report = { schemaVersion: "reef.settlementDependencyGraph.v1", eventStream: stream,
    sourceGeneration: generation[0][0], checkedAt: new Date().toISOString(),
    ...assessDependencyGraph(rows) };
  if (report.admissions === 0) throw new Error("settlement dependency graph is empty");
  writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`settlement dependency graph: ${report.admissions} admissions; max depth ${report.maxDepth}; report=${output}`);
}

if (process.argv[1]?.endsWith("settlement-dependency-graph-check.mjs")) main();
