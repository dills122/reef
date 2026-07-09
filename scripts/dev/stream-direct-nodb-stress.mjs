import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { setDefault } from "./lib/dev-utils.mjs";

import "./stream-direct-nodb-up.mjs";

setDefault("DEV_STRESS_MODE", "strict-lifecycle");
setDefault("DEV_STRESS_PROFILE", "stream-submit");
setDefault("DEV_STRESS_RATES", "5000,10000,15000,20000");
setDefault("DEV_STRESS_SWEEP_WORKERS", "256");
setDefault("DEV_STRESS_DURATION", "90s");
setDefault("DEV_STRESS_RATE_SCHEDULE", "precise");
setDefault("DEV_STRESS_RATE_QUEUE_DEPTH", "300000");
setDefault("DEV_STRESS_TRACE_CHECK_LIMIT", "0");
setDefault("DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING", "0");
setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS", "0");
setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR", "0");
setDefault("DEV_STRESS_CAPTURE_STREAM_DIRECT", "1");
setDefault("DEV_STRESS_STREAM_DIRECT_DRAIN_WAIT_MS", "30000");
setDefault("DEV_STRESS_STREAM_DIRECT_DRAIN_POLL_MS", "1000");
setDefault("DEV_STRESS_STREAM_DIRECT_PROBE_TIMEOUT_MS", "15000");
setDefault("DEV_STRESS_CAPTURE_DB_DIAGNOSTICS", "0");
setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "95");
setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-stream-direct-nodb-stress");
setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-stream-direct-nodb-stress/stream-direct-nodb-stress.json");
setDefault("DEV_STRESS_SCENARIO_ID", "stream-direct-nodb:submit");
setDefaultGeneratedSessionConfig();

console.log("stream-direct no-db stress settings:");
console.log(`  rates=${process.env.DEV_STRESS_RATES}`);
console.log(`  workers=${process.env.DEV_STRESS_SWEEP_WORKERS || process.env.DEV_STRESS_WORKERS}`);
console.log(`  duration=${process.env.DEV_STRESS_DURATION}`);
console.log(`  rateSchedule=${process.env.DEV_STRESS_RATE_SCHEDULE}`);
console.log(`  rateQueueDepth=${process.env.DEV_STRESS_RATE_QUEUE_DEPTH}`);
console.log(`  transport=${process.env.DEV_STRESS_TRANSPORT || process.env.REEF_TRANSPORT || "http"}`);
console.log(`  streamAddress=${process.env.DEV_STRESS_STREAM_ADDRESS || process.env.REEF_STREAM_ADDRESS || "127.0.0.1:8090"}`);
console.log(`  directDrainWaitMs=${process.env.DEV_STRESS_STREAM_DIRECT_DRAIN_WAIT_MS}`);
console.log(`  artifactDir=${process.env.DEV_STRESS_ARTIFACT_DIR}`);

await import("./stress.mjs");

function setDefaultGeneratedSessionConfig() {
  if (!process.env.DEV_STRESS_SESSION_CONFIG) {
    process.env.DEV_STRESS_SESSION_CONFIG = writeStreamDirectSpreadSessionConfig();
  }
}

function writeStreamDirectSpreadSessionConfig() {
  const artifactDir = process.env.DEV_STRESS_ARTIFACT_DIR || "/tmp/reef-stream-direct-nodb-stress";
  mkdirSync(artifactDir, { recursive: true });
  const path = join(artifactDir, "stream-direct-submit-spread.yaml");
  writeFileSync(path, streamDirectSpreadSessionConfig());
  return path;
}

function streamDirectSpreadSessionConfig() {
  const instrumentCount = Number(process.env.DEV_STRESS_STREAM_DIRECT_INSTRUMENTS || "64");
  const equities = Array.from({ length: Math.max(1, instrumentCount) }, (_, index) => {
    const number = index + 1;
    const symbol = `STK${String(number).padStart(3, "0")}`;
    const base = 100_000_000_000 + number * 1_000_000_000;
    return [
      `    - symbol: ${symbol}`,
      `      instrumentId: ${symbol}`,
      "      startingPriceNanos: " + base,
      "      avgDailyVolume: 10000000",
      "      sharesOutstanding: 1000000000",
      "      marketCap: " + base * 10,
      `      volatilityBps: ${90 + (number % 12) * 10}`,
      `      spreadBps: ${4 + (number % 5)}`,
    ].join("\n");
  }).join("\n");

  return `session:
  name: stream-direct-nodb-submit-stress
  scenarioRunId: stream-direct-nodb-submit-stress
  seed: 616161
  mode: strict-lifecycle

runtime:
  baseUrl: http://localhost:8080
  duration: 90s
  workers: 256
  ratePerSecond: 1000
  timeout: 5s
  traceCheckLimit: 0

market:
  timezone: America/New_York
  equities:
${equities}

actors:
  - actorId: stream-mm-01
    actorType: market_maker
    strategyId: two_sided_quote
    weight: 20
  - actorId: stream-inst-01
    actorType: institutional
    strategyId: vwap_slice
    weight: 20
  - actorId: stream-inst-02
    actorType: institutional
    strategyId: tactical_entry
    weight: 20
  - actorId: stream-retail-01
    actorType: retail
    strategyId: dip_buyer
    weight: 20
  - actorId: stream-retail-02
    actorType: retail
    strategyId: passive_limit
    weight: 20

mix:
  actions:
    submitPct: 100
    modifyPct: 0
    cancelPct: 0
  sideBias:
    buyPct: 50
    sellPct: 50
`;
}
