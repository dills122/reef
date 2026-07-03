import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import "./stream-ack-up.mjs";

setDefault("DEV_STRESS_MODE", "strict-lifecycle");
setDefault("DEV_STRESS_PROFILE", "stream-submit");
setDefault("DEV_STRESS_RATES", "1000,2500,5000");
setDefault("DEV_STRESS_SWEEP_WORKERS", "256");
setDefault("DEV_STRESS_TRACE_CHECK_LIMIT", "200");
setDefault("DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING", "0");
setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS", "1");
setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "90");
setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-stream-ack-stress");
setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-stream-ack-stress/stream-ack-stress.json");
setDefaultGeneratedSessionConfig();

await import("./stress.mjs");

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}

function setDefaultGeneratedSessionConfig() {
  if (!process.env.DEV_STRESS_SESSION_CONFIG) {
    process.env.DEV_STRESS_SESSION_CONFIG = writeStreamAckSpreadSessionConfig();
  }
}

function writeStreamAckSpreadSessionConfig() {
  const artifactDir = process.env.DEV_STRESS_ARTIFACT_DIR || "/tmp/reef-stream-ack-stress";
  mkdirSync(artifactDir, { recursive: true });
  const path = join(artifactDir, "stream-ack-submit-spread.yaml");
  writeFileSync(path, streamAckSpreadSessionConfig());
  return path;
}

function streamAckSpreadSessionConfig() {
  const equities = Array.from({ length: 64 }, (_, index) => {
    const number = index + 1;
    const symbol = `STK${String(number).padStart(3, "0")}`;
    const base = 100_000_000_000 + number * 1_000_000_000;
    return [
      `    - symbol: ${symbol}`,
      `      instrumentId: ${symbol}`,
      `      startingPriceNanos: ${base}`,
      "      avgDailyVolume: 10000000",
      "      sharesOutstanding: 1000000000",
      `      marketCap: ${base * 10}`,
      `      volatilityBps: ${90 + (number % 12) * 10}`,
      `      spreadBps: ${4 + (number % 5)}`,
    ].join("\n");
  }).join("\n");

  return `session:
  name: stream-ack-submit-stress
  scenarioRunId: stream-ack-submit-stress
  seed: 515151
  mode: strict-lifecycle

runtime:
  baseUrl: http://localhost:8080
  duration: 30s
  workers: 256
  ratePerSecond: 1000
  timeout: 5s
  traceCheckLimit: 200

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
