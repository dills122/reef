import { spawn } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import { createInterface } from "node:readline";

const loadPath = process.env.DEV_CALCIFY_LOAD_REPORT;
const reportPath = process.env.DEV_CALCIFY_VERIFY_REPORT;
if (!loadPath || !reportPath) throw new Error("DEV_CALCIFY_LOAD_REPORT and DEV_CALCIFY_VERIFY_REPORT required");
const load = JSON.parse(await readFile(loadPath, "utf8"));
const smokeId = load.smokeId;
const generation = Number(load.receiptGeneration);
if (!/^[A-Za-z0-9_-]+$/.test(smokeId) || !Number.isSafeInteger(generation) || generation < 1) {
  throw new Error("load report must identify a fresh Calcify smoke ID and receipt generation");
}
const topicPrefix = `REEF_${smokeId.toUpperCase()}`;
const source = { batches: 0, commands: 0, trades: 0, lastTimestampMs: 0 };
const commitments = { count: 0, lastTimestampMs: 0 };
const verified = { count: 0, lastTimestampMs: 0 };
for (let partition = 0; partition < 4; partition++) {
  await consume(`${topicPrefix}_EVENTS`, partition, false, (record) => {
    const batch = JSON.parse(record.value);
    source.batches++;
    source.commands += batch.commandCount;
    source.trades += batch.outcomes.reduce((sum, outcome) => sum + (outcome.result.trades?.length ?? 0), 0);
    source.lastTimestampMs = Math.max(source.lastTimestampMs, Number(record.timestamp));
  });
  for (const [suffix, summary] of [["COMMITMENTS", commitments], ["VERIFIED", verified]]) {
    await consume(`${topicPrefix}_${suffix}`, partition, true, (record) => {
      summary.count++;
      summary.lastTimestampMs = Math.max(summary.lastTimestampMs, Number(record.timestamp));
    });
  }
}
const intakeRows = Number(await psql("reef-boundary-postgres",
  `SELECT COUNT(*) FROM boundary.stream_command_intake WHERE stream_name = '${topicPrefix}_COMMANDS'`));
const receipts = Number(await psql("reef-postgres",
  `SELECT COUNT(*) FROM runtime.calcify_commitment_receipts WHERE source_generation = ${generation}`));
const receiptLastMs = Number(await psql("reef-postgres",
  `SELECT FLOOR(EXTRACT(EPOCH FROM MAX(recorded_at)) * 1000)::bigint FROM runtime.calcify_commitment_receipts WHERE source_generation = ${generation}`));
const matching = await (await fetch("http://127.0.0.1:8081/internal/stream-direct/stats")).json();
const matchingTotals = matching.partitions.reduce((totals, partition) => {
  for (const field of ["acked", "nacked", "failed"]) totals[field] += partition[field] ?? 0;
  return totals;
}, { acked: 0, nacked: 0, failed: 0 });
const expectedCommands = load.completedPairs * 2 + 2;
const expectedTrades = load.completedPairs + 1;
const result = { smokeId, generation, expectedCommands, expectedTrades,
  loadReport: loadPath, intakeRows, source, commitmentLinks: commitments.count,
  verifiedLinks: verified.count, receipts, matching: matchingTotals,
  sourceToCommitmentLastMs: commitments.lastTimestampMs - source.lastTimestampMs,
  commitmentToVerifiedLastMs: verified.lastTimestampMs - commitments.lastTimestampMs,
  verifiedToReceiptLastMs: receiptLastMs - verified.lastTimestampMs };
result.pass = load.failures === 0 && load.acceptedOrders === load.completedPairs * 2 &&
  intakeRows === expectedCommands && source.commands === expectedCommands &&
  source.trades === expectedTrades && commitments.count === expectedTrades &&
  verified.count === expectedTrades && receipts === expectedTrades &&
  matchingTotals.acked === expectedCommands && matchingTotals.nacked === 0 && matchingTotals.failed === 0;
await writeFile(reportPath, JSON.stringify(result, null, 2) + "\n");
console.log(JSON.stringify(result, null, 2));
if (!result.pass) throw new Error("Calcify high-rate full-path accounting failed");

function consume(topic, partition, metaOnly, visit) {
  return new Promise((resolve, reject) => {
    const args = ["exec", "reef-redpanda", "rpk", "topic", "consume", topic,
      "--read-committed", "-p", String(partition), "-o", ":end", "--format", "json", "--pretty-print=false"];
    if (metaOnly) args.push("--meta-only");
    const child = spawn("docker", args, { stdio: ["ignore", "pipe", "pipe"] });
    let failure = null;
    let stderr = "";
    const timer = setTimeout(() => child.kill("SIGTERM"), 300000);
    createInterface({ input: child.stdout, crlfDelay: Infinity }).on("line", (line) => {
      if (!line || failure) return;
      try { visit(JSON.parse(line)); }
      catch (error) { failure = error; child.kill("SIGTERM"); }
    });
    child.stderr.on("data", (chunk) => { stderr = (stderr + chunk).slice(-4096); });
    child.on("error", (error) => { clearTimeout(timer); reject(error); });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (failure) reject(failure);
      else if (code !== 0) reject(new Error(`rpk consume ${topic}/${partition} failed (${code}): ${stderr}`));
      else resolve();
    });
  });
}

async function psql(container, query) {
  const child = spawn("docker", ["exec", container, "psql", "-U", "reef", "-d", "reef", "-At", "-c", query],
    { stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdout += chunk; });
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const code = await new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("close", resolve);
  });
  if (code !== 0) throw new Error(`psql failed (${code}): ${stderr}`);
  return stdout.trim();
}
