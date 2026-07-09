import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export function setDefaultGeneratedSpreadSubmitSessionConfig(options) {
  if (process.env.DEV_STRESS_SESSION_CONFIG) return;
  process.env.DEV_STRESS_SESSION_CONFIG = writeSpreadSubmitSessionConfig(options);
}

function writeSpreadSubmitSessionConfig(options) {
  const artifactDir = process.env.DEV_STRESS_ARTIFACT_DIR || options.artifactDir;
  mkdirSync(artifactDir, { recursive: true });
  const path = join(artifactDir, options.fileName);
  writeFileSync(path, spreadSubmitSessionConfig(options));
  return path;
}

function spreadSubmitSessionConfig(options) {
  const instrumentCount = Number(process.env[options.instrumentCountEnv] || "64");
  const equities = Array.from({ length: Math.max(1, instrumentCount) }, (_, index) => {
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
  name: ${options.sessionName}
  scenarioRunId: ${options.scenarioRunId}
  seed: ${options.seed}
  mode: strict-lifecycle

runtime:
  baseUrl: http://localhost:8080
  duration: ${options.duration}
  workers: 256
  ratePerSecond: 1000
  timeout: 5s
  traceCheckLimit: ${options.traceCheckLimit}

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
