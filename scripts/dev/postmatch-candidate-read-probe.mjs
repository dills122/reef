#!/usr/bin/env node
// Bounded, loopback-only in-load HTTP probe. Raw samples retain failures and as-of metadata.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const quantile = (sorted, q) => sorted.length ? sorted[Math.ceil(sorted.length * q) - 1] : null;
const PRIVATE_READS = new Set(["balance", "status"]);
const MARKET_READS = new Set(["book", "depth", "tape"]);
export const MARKET_AGE_GATES = { p95Ms: 5000, p99Ms: 10000, maxMs: 30000 };

function probeHeaders(name, participantId, url) {
  const headers = { "X-Client-Id": "candidate-read-probe" };
  if (participantId && PRIVATE_READS.has(name)) headers["X-Participant-Id"] = participantId;
  const target = new URL(url);
  if (name === "status" && process.env.ADMIN_API_TOKEN &&
      target.protocol === "http:" && ["127.0.0.1", "[::1]", "localhost"].includes(target.hostname)) {
    headers.Authorization = `Bearer ${process.env.ADMIN_API_TOKEN}`;
  }
  return headers;
}

export function hasCompleteAsOf(name, asOf) {
  if (!asOf || typeof asOf !== "object") return false;
  const market = ["book", "depth", "tape"].includes(name);
  return (market ? Number.isFinite(asOf.projectedSourceSequence) &&
    Number.isFinite(asOf.observedSourceSequence) &&
    Number.isFinite(asOf.sourceLagPositions) &&
    typeof asOf.sourceGeneration === "string" &&
    typeof asOf.projectorGeneration === "string" :
    Number.isFinite(asOf.batchSequence) &&
    Number.isFinite(asOf.observedJournalSequence) &&
    Number.isFinite(asOf.journalLagBatches) &&
    typeof asOf.journalIncarnationId === "string") &&
    typeof asOf.currentAtObservation === "boolean";
}

export function summarizeSamples(samples, gates = null) {
  const names = [...new Set(samples.map((sample) => sample.name))].sort();
  const byName = Object.fromEntries(names.map((name) => {
    const group = samples.filter((sample) => sample.name === name);
    const good = group.filter((sample) => sample.ok && sample.asOf);
    const latencies = good.map((sample) => sample.latencyMs).sort((a, b) => a - b);
    const upperBounds = good.filter((sample) => Number.isFinite(sample.sourceAgeUpperBoundMs))
      .map((sample) => sample.sourceAgeUpperBoundMs).sort((a, b) => a - b);
    return [name, {
      samples: group.length, failures: group.length - good.length,
      valuesPresent: good.filter((sample) => sample.valuePresent === true).length,
      notCurrent: good.filter((sample) => sample.currentAtObservation !== true).length,
      latencyP50Ms: quantile(latencies, 0.5),
      latencyP95Ms: quantile(latencies, 0.95),
      latencyP99Ms: quantile(latencies, 0.99),
      sourceAgeUpperBoundP95Ms: quantile(upperBounds, 0.95),
      sourceAgeUpperBoundP99Ms: quantile(upperBounds, 0.99),
      sourceAgeUpperBoundMaxMs: upperBounds.at(-1) ?? null,
      upperBoundSamples: upperBounds.length,
    }];
  }));
  const required = ["book", "depth", "tape", "balance", "status"];
  const hasGates = gates && ["latencyP95Ms", "latencyP99Ms"]
    .every((key) => Number.isFinite(gates[key]) && gates[key] > 0);
  const readGatePassed = hasGates && required.every((name) => {
    const row = byName[name];
    return row && row.samples > 0 && row.failures === 0 &&
      (name !== "balance" && name !== "status" || row.valuesPresent > 0) &&
      (!MARKET_READS.has(name) || row.upperBoundSamples >= Math.ceil(row.samples * 0.95) &&
        row.sourceAgeUpperBoundP95Ms <= MARKET_AGE_GATES.p95Ms &&
        row.sourceAgeUpperBoundP99Ms <= MARKET_AGE_GATES.p99Ms &&
        row.sourceAgeUpperBoundMaxMs <= MARKET_AGE_GATES.maxMs) &&
      row.latencyP95Ms <= gates.latencyP95Ms && row.latencyP99Ms <= gates.latencyP99Ms;
  });
  return { byName, readGatePassed: Boolean(readGatePassed),
    readGateReason: !hasGates ? "numeric read gates not frozen" :
      !readGatePassed ? "missing endpoint, read failure, API age, or latency limit breach" : "passed",
    marketAgeGates: MARKET_AGE_GATES,
    ageGateAuthority: "API source-age upper bound plus separate projection window-commit measurements" };
}

export function parseArgs(argv) {
  const urls = new Map();
  const options = { durationSeconds: 300, intervalMs: 1000, timeoutMs: 5000,
    output: null, gates: null, participantId: null };
  const gateValues = {};
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const value = argv[++i];
    if (value === undefined) throw new Error(`missing value for ${flag}`);
    if (flag === "--url") {
      const separator = value.indexOf("=");
      if (separator < 1) throw new Error("--url expects name=http://127.0.0.1/path");
      const name = value.slice(0, separator);
      if (!/^[a-z][a-z0-9-]*$/.test(name) || urls.has(name)) throw new Error("invalid duplicate probe name");
      const url = new URL(value.slice(separator + 1));
      if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]"].includes(url.hostname) ||
        url.username || url.password) throw new Error("probe URL must be unauthenticated loopback HTTP");
      urls.set(name, url.toString());
    } else if (flag === "--duration-seconds") options.durationSeconds = Number(value);
    else if (flag === "--interval-ms") options.intervalMs = Number(value);
    else if (flag === "--timeout-ms") options.timeoutMs = Number(value);
    else if (flag === "--participant-id") {
      if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)) {
        throw new Error("--participant-id must be a bounded header-safe identifier");
      }
      options.participantId = value;
    }
    else if (flag === "--output") options.output = resolve(value);
    else if (flag === "--latency-p95-ms") gateValues.latencyP95Ms = Number(value);
    else if (flag === "--latency-p99-ms") gateValues.latencyP99Ms = Number(value);
    else throw new Error(`unknown probe option ${flag}`);
  }
  if (urls.size === 0 || !Number.isInteger(options.durationSeconds) ||
    options.durationSeconds < 1 || options.durationSeconds > 3600 ||
    !Number.isInteger(options.intervalMs) || options.intervalMs < 50 || options.intervalMs > 10_000 ||
    !Number.isInteger(options.timeoutMs) || options.timeoutMs < 100 || options.timeoutMs > 60_000) {
    throw new Error("probe requires URLs and bounded duration, interval, timeout");
  }
  if (Object.keys(gateValues).length > 0) {
    if (Object.keys(gateValues).length !== 2 || Object.values(gateValues)
      .some((value) => !Number.isFinite(value) || value <= 0)) {
      throw new Error("both positive numeric read latency gates are required together");
    }
    options.gates = gateValues;
  }
  return { ...options, urls };
}

export async function probeOnce(name, url, timeoutMs, participantId = null, fetchImpl = fetch) {
  const started = performance.now();
  const sampledAt = new Date().toISOString();
  try {
    const response = await fetchImpl(url, { signal: AbortSignal.timeout(timeoutMs),
      headers: probeHeaders(name, participantId, url) });
    const payload = await response.json();
    const asOf = payload.asOf ?? null;
    const validAsOf = hasCompleteAsOf(name, asOf);
    return { name, sampledAt, ok: response.ok && Boolean(validAsOf), status: response.status,
      latencyMs: performance.now() - started, asOf: validAsOf ? asOf : null,
      currentAtObservation: validAsOf ? asOf.currentAtObservation : null,
      sourceAgeUpperBoundMs: validAsOf ? asOf.sourceAgeUpperBoundMs : null,
      valuePresent: name === "balance" ? payload.balance != null :
        name === "status" ? payload.settlement != null : true,
      discoveredTradeId: name === "tape" && response.ok && validAsOf ?
        payload.trades?.find((trade) => typeof trade.tradeId === "string" && trade.tradeId)?.tradeId ?? null : null };
  } catch (error) {
    return { name, sampledAt, ok: false, status: null,
      latencyMs: performance.now() - started, asOf: null,
      error: error instanceof Error ? error.message : String(error) };
  }
}

export async function runProbe(options) {
  const samples = [];
  const deadline = performance.now() + options.durationSeconds * 1000;
  let discoveredTradeId = null;
  while (performance.now() < deadline) {
    const cycle = performance.now();
    const batch = await Promise.all([...options.urls].filter(([name]) => name !== "status")
      .map(([name, url]) => probeOnce(name, url, options.timeoutMs, options.participantId)));
    samples.push(...batch);
    discoveredTradeId ??= batch.find((sample) => sample.name === "tape")?.discoveredTradeId ?? null;
    const statusUrl = options.urls.get("status");
    if (statusUrl) {
      if (statusUrl.includes("__TRADE_ID__") && discoveredTradeId) {
        samples.push(await probeOnce("status", statusUrl.replace("__TRADE_ID__",
          encodeURIComponent(discoveredTradeId)), options.timeoutMs, options.participantId));
      } else if (!statusUrl.includes("__TRADE_ID__")) {
        samples.push(await probeOnce("status", statusUrl, options.timeoutMs, options.participantId));
      }
    }
    const wait = options.intervalMs - (performance.now() - cycle);
    if (wait > 0) await new Promise((done) => setTimeout(done, wait));
  }
  const summary = summarizeSamples(samples, options.gates);
  const artifact = { workload: { durationSeconds: options.durationSeconds,
    intervalMs: options.intervalMs, timeoutMs: options.timeoutMs,
    probeNames: [...options.urls.keys()], discoveredTradeId }, gates: options.gates,
    summary, samples };
  if (options.output) {
    mkdirSync(dirname(options.output), { recursive: true });
    writeFileSync(options.output, JSON.stringify(artifact, null, 2) + "\n");
  }
  return artifact;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    const options = parseArgs(process.argv.slice(2));
    const artifact = await runProbe(options);
    process.stdout.write(JSON.stringify(artifact.summary) + "\n");
    if (options.gates && !artifact.summary.readGatePassed) process.exitCode = 1;
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 2;
  }
}
