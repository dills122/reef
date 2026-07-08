import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";
import { composeArgs } from "./lib/compose-utils.mjs";
import { printStreamProfileSummary, validateStreamProfile } from "./lib/stream-profile-guard.mjs";
import { selectPartitionSpreadInstruments } from "./lib/stream-partition-spread.mjs";

const MATERIALIZER_STRESS_SESSION_ID = "venue-event-materializer-mixed-lifecycle-stress";
const MATERIALIZER_STRESS_RUN_ID = "venue-event-materializer-mixed-lifecycle-stress";

loadDotEnv();

// Inlines the stream-direct no-db setup (services/matching-engine consumes the durable command
// stream directly, Postgres stays out of the matching hot path) instead of importing
// stream-direct-nodb-up.mjs, because that script unconditionally forces
// STREAM_ACK_PROJECTOR_ENABLED=false. This script and the ablation ladder need that flag
// controllable per rung, so it is left as a plain setDefault below instead.
setValue("RUNTIME_PERSISTENCE", "noop");
setValue("EXTERNAL_API_IDEMPOTENCY_STORE", "inmemory");
setValue("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
setValue("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
setValue("STREAM_ACK_INTAKE_STORE", "inmemory");
setDefault("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES", "100000");
setDefault("STREAM_ACK_INMEMORY_INTAKE_SHARDS", "256");
setValue("PLATFORM_HTTP_SERVER", "netty");
setValue("STREAM_ACK_LOG_PROVIDER", "redpanda");
setDefault("STREAM_ACK_COMMAND_STREAM", "REEF_MATERIALIZER_STRESS_COMMANDS");
setDefault("STREAM_ACK_SUBJECT_PREFIX", "reef.materializer.stress.cmd.v1");
setDefault("STREAM_ACK_COMMAND_STREAM_MAX_BYTES", "34359738368");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "true");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY", "8192");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE", "256");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE", "1");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS", "0");
setDefault("STREAM_ACK_PARTITION_COUNT", "16");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..15");
setValue("STREAM_ACK_WORKER_ENABLED", "false");
setDefault("STREAM_ACK_PROJECTOR_ENABLED", "false");
setValue("MATCHING_ENGINE_DIRECT_STREAM_ENABLED", "true");
setValue("PLATFORM_INTERNAL_HTTP_MODE", "enabled");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE", "500");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_FETCH_TIMEOUT_MS", "100");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_POLL_MS", "1");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING", "16000");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_ACK_WAIT_MS", "60000");
setDefault("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", "250000");
setValue("EXTERNAL_API_ABUSE_BREAKER_MODE", "off");
setValue("VENUE_EVENT_MATERIALIZER_ENABLED", "true");
setDefault("MATCHING_ENGINE_EVENT_STREAM", "REEF_MATERIALIZER_STRESS_VENUE_EVENTS");
setDefault("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", "reef.materializer.stress.venue.events.v1");
setDefault("VENUE_EVENT_MATERIALIZER_TOPIC", env("MATCHING_ENGINE_EVENT_STREAM"));
setDefault("VENUE_EVENT_MATERIALIZER_GROUP_ID", "reef-venue-event-materializer-stress");
setDefault("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", "1000");
setDefault("VENUE_EVENT_MATERIALIZER_POLL_MS", "10");
setDefault("VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS", "200");
setDefault("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), ["redpanda", "venue-event-materializer", "venue-event-materializer-scaled"]));

setDefault("DEV_STRESS_MODE", "strict-lifecycle");
setDefault("DEV_STRESS_PROFILE", "capacity-heavy");
setDefault("DEV_STRESS_RATES", "10000");
setDefault("DEV_STRESS_SWEEP_WORKERS", "384");
setDefault("DEV_STRESS_DURATION", "180s");
setDefault("DEV_STRESS_RATE_SCHEDULE", "precise");
setDefault("DEV_STRESS_RATE_QUEUE_DEPTH", "300000");
setDefault("DEV_STRESS_TRACE_CHECK_LIMIT", "0");
setDefault("DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING", "0");
setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS", "0");
setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR", "0");
setDefault("DEV_STRESS_CAPTURE_STREAM_DIRECT", "1");
setDefault("DEV_STRESS_FAIL_ON_STREAM_DIRECT_FAILURES", "1");
setDefault("DEV_STRESS_MAX_STREAM_DIRECT_COMPLETION_GAP", "0");
setDefault("DEV_STRESS_STREAM_DIRECT_DRAIN_WAIT_MS", "30000");
setDefault("DEV_STRESS_STREAM_DIRECT_DRAIN_POLL_MS", "1000");
setDefault("DEV_STRESS_STREAM_DIRECT_PROBE_TIMEOUT_MS", "15000");
setDefault("DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER", "1");
setDefault("DEV_STRESS_VENUE_EVENT_MATERIALIZER_URLS", defaultMaterializerUrls());
setDefault("DEV_STRESS_FAIL_ON_VENUE_EVENT_MATERIALIZER_FAILURES", "1");
setDefault("DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_COMPLETION_GAP", "0");
setDefault("DEV_STRESS_VENUE_EVENT_MATERIALIZER_DRAIN_WAIT_MS", "60000");
setDefault("DEV_STRESS_VENUE_EVENT_MATERIALIZER_DRAIN_POLL_MS", "1000");
setDefault("DEV_STRESS_VENUE_EVENT_MATERIALIZER_PROBE_TIMEOUT_MS", "15000");
setDefault("DEV_STRESS_CAPTURE_DB_DIAGNOSTICS", "1");
setDefault("DEV_STRESS_DB_SERVICES", "postgres");
setDefault("DEV_STRESS_RUN_ID", MATERIALIZER_STRESS_RUN_ID);
setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "95");
setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-venue-event-materializer-stress");
setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-venue-event-materializer-stress/venue-event-materializer-stress.json");
setDefault("DEV_STRESS_SCENARIO_ID", "venue-event-materializer:mixed-lifecycle");
setDefault("DEV_STRESS_STOP_IDLE_BACKGROUND_SERVICES", "1");
setDefaultGeneratedSessionConfig();

console.log("venue-event-materializer durable-canonical stress settings:");
console.log(`  rates=${process.env.DEV_STRESS_RATES}`);
console.log(`  workers=${process.env.DEV_STRESS_SWEEP_WORKERS || process.env.DEV_STRESS_WORKERS}`);
console.log(`  duration=${process.env.DEV_STRESS_DURATION}`);
console.log(`  profile=${process.env.DEV_STRESS_PROFILE}`);
console.log(`  routingRunId=${process.env.DEV_STRESS_RUN_ID}`);
console.log(`  materializerBatchSize=${process.env.VENUE_EVENT_MATERIALIZER_BATCH_SIZE}`);
console.log(`  materializerUrls=${process.env.DEV_STRESS_VENUE_EVENT_MATERIALIZER_URLS}`);
console.log(`  materializerDrainWaitMs=${process.env.DEV_STRESS_VENUE_EVENT_MATERIALIZER_DRAIN_WAIT_MS}`);
console.log(`  terminalOrderRetentionLimit=${process.env.MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT}`);
console.log(`  dbDiagnostics=${process.env.DEV_STRESS_CAPTURE_DB_DIAGNOSTICS}`);
console.log(`  artifactDir=${process.env.DEV_STRESS_ARTIFACT_DIR}`);
console.log(`  stopIdleBackgroundServices=${process.env.DEV_STRESS_STOP_IDLE_BACKGROUND_SERVICES}`);
validateStreamProfile("materializer-soak");
printStreamProfileSummary("materializer-soak");

await import("./stream-ack-up.mjs");
await stopIdleBackgroundServices();
await import("./stress.mjs");

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}

function setValue(name, value) {
  process.env[name] = value;
}

function appendProfiles(raw, additions) {
  const profiles = new Set(
    String(raw ?? "")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean),
  );
  for (const addition of additions) {
    profiles.add(addition);
  }
  return [...profiles].join(",");
}

function defaultMaterializerUrls() {
  const urls = [
    `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_HOST_PORT", "8091")}`,
    `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_1_HOST_PORT", "8092")}`,
    `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_2_HOST_PORT", "8093")}`,
    `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_3_HOST_PORT", "8094")}`,
  ];
  return hasProfile("venue-event-materializer-scaled") ? urls.join(",") : urls[0];
}

function hasProfile(profile) {
  return String(process.env.DEV_COMPOSE_PROFILES ?? "")
    .split(",")
    .map((value) => value.trim())
    .includes(profile);
}

async function stopIdleBackgroundServices() {
  if (process.env.DEV_STRESS_STOP_IDLE_BACKGROUND_SERVICES !== "1") return;
  const services = [];
  if (process.env.STREAM_ACK_WORKER_ENABLED === "false") {
    services.push("platform-worker-0", "platform-worker-1", "platform-worker-2", "platform-worker-3");
  }
  if (process.env.STREAM_ACK_PROJECTOR_ENABLED === "false") {
    services.push("platform-projector-0", "platform-projector-1", "platform-projector-2", "platform-projector-3");
  }
  if (services.length === 0) return;

  console.log(`stopping idle background services before stress: ${services.join(",")}`);
  await run("docker", composeArgs(["stop", ...services]));
}

function setDefaultGeneratedSessionConfig() {
  if (!process.env.DEV_STRESS_SESSION_CONFIG) {
    process.env.DEV_STRESS_SESSION_CONFIG = writeMaterializerSpreadSessionConfig();
  }
}

function writeMaterializerSpreadSessionConfig() {
  const artifactDir = process.env.DEV_STRESS_ARTIFACT_DIR || "/tmp/reef-venue-event-materializer-stress";
  mkdirSync(artifactDir, { recursive: true });
  const path = join(artifactDir, "venue-event-materializer-spread.yaml");
  writeFileSync(path, materializerSpreadSessionConfig());
  return path;
}

function materializerSpreadSessionConfig() {
  const instrumentCount = Number(process.env.DEV_STRESS_MATERIALIZER_INSTRUMENTS || "64");
  const instruments = selectPartitionSpreadInstruments({
    runId: env("DEV_STRESS_RUN_ID", MATERIALIZER_STRESS_RUN_ID),
    venueSessionId: MATERIALIZER_STRESS_SESSION_ID,
    requestedCount: Math.max(1, instrumentCount),
    partitionCount: Number(env("STREAM_ACK_PARTITION_COUNT", "16")),
  });
  const equities = instruments.map(({ number, symbol }) => {
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
  name: ${MATERIALIZER_STRESS_SESSION_ID}
  scenarioRunId: ${MATERIALIZER_STRESS_RUN_ID}
  seed: 727272
  mode: strict-lifecycle

runtime:
  baseUrl: http://localhost:8080
  duration: 180s
  workers: 384
  ratePerSecond: 1000
  timeout: 5s
  traceCheckLimit: 0

market:
  timezone: America/New_York
  equities:
${equities}

actors:
  - actorId: materializer-mm-01
    actorType: market_maker
    strategyId: two_sided_quote
    weight: 20
  - actorId: materializer-inst-01
    actorType: institutional
    strategyId: vwap_slice
    weight: 20
  - actorId: materializer-inst-02
    actorType: institutional
    strategyId: tactical_entry
    weight: 20
  - actorId: materializer-retail-01
    actorType: retail
    strategyId: dip_buyer
    weight: 20
  - actorId: materializer-retail-02
    actorType: retail
    strategyId: passive_limit
    weight: 20

mix:
  actions:
    submitPct: 68
    modifyPct: 24
    cancelPct: 8
  sideBias:
    buyPct: 50
    sellPct: 50
`;
}
