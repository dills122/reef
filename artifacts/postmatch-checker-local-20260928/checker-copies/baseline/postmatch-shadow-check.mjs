#!/usr/bin/env node
// Read-only closed-cohort check for the opt-in disposable-host shadow run.
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";

const compose = ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml", "--profile", "postmatch"];

export function assess(source, target, partitions, generation) {
  const failures = [];
  const assigned = new Set(partitions);
  for (const partition of source.keys()) {
    if (!assigned.has(partition)) failures.push(`source partition ${partition} is not assigned to a shadow worker`);
  }
  const rows = partitions.map((partition) => {
    const origin = (BigInt(partition) << 48n).toString();
    const sourceRow = source.get(partition) ?? { count: "0", sequence: origin };
    const live = target.get(`live-v1:${partition}`);
    const market = target.get(`live-market-v1:${partition}`);
    for (const [name, frontier] of [["live", live], ["market", market]]) {
      if (!frontier) failures.push(`${name} frontier missing for partition ${partition}`);
      else if (frontier.generation !== generation) failures.push(`${name} source generation mismatch for partition ${partition}`);
      else if (BigInt(frontier.sequence) < BigInt(sourceRow.sequence)) failures.push(`${name} frontier behind source for partition ${partition}`);
    }
    if (live && market && BigInt(market.sequence) < BigInt(live.sequence)) {
      failures.push(`market frontier behind live for partition ${partition}`);
    }
    return { partition, sourceCount: sourceRow.count, sourceSequence: sourceRow.sequence,
      liveSequence: live?.sequence ?? null, marketSequence: market?.sequence ?? null };
  });
  if (rows.every((row) => row.sourceCount === "0")) failures.push("source cohort is empty");
  return { rows, failures };
}

function sqlLiteral(value) { return `'${value.replaceAll("'", "''")}'`; }

function psqlArgs(service, sql) {
  return [...compose, "exec", "-T", service, "psql", "-X", "-A", "-t", "-F", "\t",
    "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", process.env.PM10_DB || "reef", "-c", sql];
}

function query(service, sql) {
  const result = spawnSync("docker", psqlArgs(service, sql), { encoding: "utf8", maxBuffer: 2 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${service} query failed: ${result.stderr?.trim() || result.error?.message}`);
  return result.stdout.trim() ? result.stdout.trim().split("\n").map((line) => line.split("\t")) : [];
}

function readSource(stream) {
  const quoted = sqlLiteral(stream);
  const generationRows = query("postgres", "SELECT generation::text FROM runtime.postmatch_source_generation WHERE singleton = TRUE");
  if (generationRows.length !== 1) throw new Error("source generation missing");
  const generation = generationRows[0][0];
  const source = new Map(query("postgres", `SELECT partition_id, count(*)::text, max(stream_sequence)::text
    FROM runtime.canonical_command_outcomes WHERE event_stream = ${quoted}
    GROUP BY partition_id ORDER BY partition_id`).map(([partition, count, sequence]) => [Number(partition), { count, sequence }]));
  return { generation, source };
}

function readTarget(stream, partitions) {
  const ids = partitions.join(",");
  const quoted = sqlLiteral(stream);
  const target = new Map(query("postmatch-postgres", `SELECT consumer_name, partition_id, source_generation, last_stream_sequence::text
    FROM postmatch.consumer_frontiers WHERE event_stream = ${quoted} AND partition_id IN (${ids})
    AND consumer_name IN ('live-v1', 'live-market-v1')`).map(([name, partition, rowGeneration, sequence]) =>
    [`${name}:${partition}`, { generation: rowGeneration, sequence }]));
  return target;
}

async function hashMembership(service, sql) {
  const child = spawn("docker", psqlArgs(service, `COPY (${sql}) TO STDOUT WITH (FORMAT csv)`), { stdio: ["ignore", "pipe", "pipe"] });
  const hash = createHash("sha256");
  let bytes = 0;
  let stderr = "";
  child.stdout.on("data", (chunk) => { hash.update(chunk); bytes += chunk.length; });
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
  const code = await new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("close", resolve);
  });
  if (code !== 0) throw new Error(`${service} membership query failed: ${stderr.trim()}`);
  return { sha256: hash.digest("hex"), bytes };
}

async function main() {
  const [stream, partitionText, output, waitText = "120"] = process.argv.slice(2);
  if (!stream || !/^[A-Za-z0-9_]+$/.test(stream) || !output) {
    throw new Error("usage: postmatch-shadow-check.mjs EVENT_STREAM PARTITIONS OUTPUT_JSON [WAIT_SECONDS]");
  }
  const partitions = partitionText?.split(",").map(Number);
  if (!partitions?.length || partitions.some((p) => !Number.isInteger(p) || p < 0 || p > 32767) ||
      new Set(partitions).size !== partitions.length) throw new Error("partitions must be distinct IDs in 0..32767");
  const waitSeconds = Number(waitText);
  if (!Number.isInteger(waitSeconds) || waitSeconds < 0 || waitSeconds > 600) throw new Error("invalid wait seconds");
  const report = { schemaVersion: "reef.postmatchShadowDiagnostic.v1", eventStream: stream,
    partitions, checkedAt: null, status: "fail", failures: [] };
  try {
    const { generation, source } = readSource(stream);
    report.generation = generation;
    const deadline = Date.now() + waitSeconds * 1000;
    do {
      Object.assign(report, assess(source, readTarget(stream, partitions), partitions, generation));
      if (report.failures.length === 0) break;
      if (Date.now() >= deadline) break;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    } while (true);
    if (report.failures.length === 0) {
      const quoted = sqlLiteral(stream);
      const ids = partitions.join(",");
      const columns = "partition_id, stream_sequence, batch_id, command_id";
      report.sourceMembership = await hashMembership("postgres", `SELECT ${columns}, payload_hash
        FROM runtime.canonical_command_outcomes WHERE event_stream = ${quoted} AND partition_id IN (${ids})
        ORDER BY partition_id, stream_sequence, batch_id, command_id, payload_hash`);
      report.liveMembership = await hashMembership("postmatch-postgres", `SELECT ${columns}, command_payload_hash
        FROM postmatch.consumer_outcome_receipts WHERE consumer_name = 'live-v1' AND event_stream = ${quoted}
        AND source_generation = ${sqlLiteral(report.generation)} AND partition_id IN (${ids})
        ORDER BY partition_id, stream_sequence, batch_id, command_id, command_payload_hash`);
      report.sourceMembershipAfter = await hashMembership("postgres", `SELECT ${columns}, payload_hash
        FROM runtime.canonical_command_outcomes WHERE event_stream = ${quoted} AND partition_id IN (${ids})
        ORDER BY partition_id, stream_sequence, batch_id, command_id, payload_hash`);
      if (report.sourceMembership.sha256 !== report.liveMembership.sha256 ||
          report.sourceMembership.bytes !== report.liveMembership.bytes) {
        report.failures.push("live receipt membership differs from canonical source");
      }
      if (report.sourceMembership.sha256 !== report.sourceMembershipAfter.sha256 ||
          report.sourceMembership.bytes !== report.sourceMembershipAfter.bytes) {
        report.failures.push("canonical source membership changed during closed-cohort check");
      }
      const after = readSource(stream);
      if (after.generation !== generation || after.source.size !== source.size ||
          [...source].some(([partition, row]) => {
            const latest = after.source.get(partition);
            return latest?.count !== row.count || latest?.sequence !== row.sequence;
          })) {
        report.failures.push("canonical source changed during closed-cohort check");
      }
    }
    report.status = report.failures.length ? "fail" : "pass";
  } catch (error) {
    report.failures.push(error.message);
  } finally {
    report.checkedAt = new Date().toISOString();
    writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
    console.log(`post-match shadow diagnostic: ${report.status}; report=${output}`);
    for (const failure of report.failures) console.error(failure);
  }
  if (report.status !== "pass") process.exitCode = 1;
}

if (process.argv[1]?.endsWith("postmatch-shadow-check.mjs")) await main();
