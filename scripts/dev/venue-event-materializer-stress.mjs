import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawn } from "node:child_process";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

import { env, loadDotEnv, run, setDefault, setValue } from "./lib/dev-utils.mjs";
import { composeArgs } from "./lib/compose-utils.mjs";
import { runStackUp } from "./lib/dev-stack-profiles.mjs";
import { configureProjectionSourceNames, printStreamProfileSummary, validateStreamProfile } from "./lib/stream-profile-guard.mjs";
import { selectPartitionSpreadInstruments, streamRoutingPartition } from "./lib/stream-partition-spread.mjs";

const MATERIALIZER_STRESS_SESSION_ID = "venue-event-materializer-mixed-lifecycle-stress";
const MATERIALIZER_STRESS_RUN_ID = "venue-event-materializer-mixed-lifecycle-stress";

loadDotEnv();
const journalCandidateRequested = env("REEF_DO_POSTMATCH_JOURNAL_DIAGNOSTIC", "0") === "1";
if (journalCandidateRequested) {
  // Start storage, broker, and source first; enroll only while both topics are cold.
  for (const key of ["POSTMATCH_SETTLEMENT_JOURNAL_CANDIDATE_ENABLED",
    "POSTMATCH_SETTLEMENT_CONTROL_AUTHORITY_ENABLED",
    "POSTMATCH_MATCHING_MARKET_CANDIDATE_ENABLED",
    "POSTMATCH_JOURNAL_PROJECTION_CANDIDATE_ENABLED",
    "POSTMATCH_CANDIDATE_READS_ENABLED"]) setValue(key, "false");
}
if (env("REEF_DO_POSTMATCH_SETTLEMENT_DIAGNOSTIC", "0") === "1" &&
    env("REEF_SETTLEMENT_POSTGRES_MIGRATIONS", "0") !== "1") {
  throw new Error("settlement diagnostic requires REEF_SETTLEMENT_POSTGRES_MIGRATIONS=1");
}

// Inlines the stream-direct no-db setup (services/matching-engine consumes the durable command
// stream directly, Postgres stays out of the matching hot path) instead of using the
// stream-direct-nodb stack profile, because that profile unconditionally forces
// STREAM_ACK_PROJECTOR_ENABLED=false. This script and the ablation ladder need that flag
// controllable per rung, so it is left as a plain setDefault below instead.
setValue("RUNTIME_PERSISTENCE", "noop");
// The load tester seeds reference data through the authenticated admin gateway.
// Keep the local stress profile runnable from the documented empty-token .env
// shape while allowing callers to replace this with their own local token.
setDefault("ADMIN_API_TOKEN", "local-materializer-stress-admin");
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
configureProjectionSourceNames();
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
setValue("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), ["redpanda", "venue-event-materializer", "venue-event-materializer-scaled"]));

setDefault("DEV_STRESS_MODE", "strict-lifecycle");
setDefault("DEV_STRESS_RUN_PROFILE", "materializer-soak");
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

await runStackUp("stream-ack");
if (journalCandidateRequested) {
  await startJournalCandidate();
  configureCandidateReadProbe();
}
if (env("REEF_DO_POSTMATCH_SETTLEMENT_DIAGNOSTIC", "0") === "1") {
  await run("node", ["scripts/dev/settlement-shadow-seed.mjs",
    env("DEV_STRESS_SESSION_CONFIG"), env("DEV_STRESS_RUN_ID")]);
}

async function startJournalCandidate() {
  const command = composeArgs(["run", "-T", "--rm", "--no-deps",
    "-e", `MATCHING_ENGINE_EVENT_STREAM=${env("MATCHING_ENGINE_EVENT_STREAM")}`,
    "--entrypoint", "java",
    "platform-api", "-cp", "/app/platform-runtime/lib/*",
    "com.reef.platform.api.PostMatchCandidateSourceEnrollmentMain"]);
  const { stdout } = await run("docker", command, { passthrough: false });
  const enrollment = stdout.split("\n").map((line) => {
    try { return JSON.parse(line); } catch { return null; }
  }).find((value) => value?.type === "settlement_source_enrollment");
  if (!enrollment || enrollment.eventStream !== env("POSTMATCH_EVENT_STREAM") ||
      enrollment.partitionCount !== 16 || !/^[0-9a-f]{64}$/.test(enrollment.bindingDigest ?? "")) {
    throw new Error("cold settlement source enrollment did not return a verified 16-partition binding");
  }
  const frontiers = Object.entries(enrollment.frontiers ?? {})
    .sort(([left], [right]) => Number(left) - Number(right))
    .map(([partition, sequence]) => `${partition}=${sequence}`);
  if (frontiers.length !== 16) throw new Error("cold source enrollment lacks 16 genesis frontiers");
  setValue("POSTMATCH_LEDGER_CONTROL_SOURCE_GENERATION", enrollment.sourceGeneration);
  setValue("POSTMATCH_SOURCE_GENERATION", enrollment.sourceGeneration);
  setValue("POSTMATCH_LEDGER_CONTROL_GENESIS_FRONTIERS", frontiers.join(","));
  setValue("POSTMATCH_LEDGER_CONTROL_INCARNATION", randomUUID());
  setValue("POSTMATCH_LEDGER_CONTROL_OWNER_NONCE", randomUUID());
  setValue("POSTMATCH_LEDGER_CONTROL_OWNER_EPOCH", "1");
  setValue("POSTMATCH_JOURNAL_INCARNATION", randomUUID());
  setValue("POSTMATCH_LEDGER_CANDIDATE_GENERATION", randomUUID());
  setValue("POSTMATCH_SETTLEMENT_CONTROL_AUTHORITY_ENABLED", "true");
  setValue("POSTMATCH_MATCHING_MARKET_CANDIDATE_ENABLED", "true");
  setValue("POSTMATCH_JOURNAL_PROJECTION_CANDIDATE_ENABLED", "true");
  setValue("POSTMATCH_SETTLEMENT_JOURNAL_CANDIDATE_ENABLED", "true");
  setValue("POSTMATCH_CANDIDATE_READS_ENABLED", "true");
  console.log(`journal candidate enrolled source generation=${enrollment.sourceGeneration} binding=${enrollment.bindingDigest}`);
  await run("docker", composeArgs(["up", "-d", "--no-deps", "--force-recreate", "--wait",
    "platform-api", "platform-postmatch-live-0", "platform-postmatch-live-1",
    "platform-postmatch-live-2", "platform-postmatch-live-3",
    "platform-postmatch-settlement-0"]));
  const { stdout: seedOutput } = await run("node", [
    "scripts/dev/settlement-candidate-control-seed.mjs",
    env("DEV_STRESS_SESSION_CONFIG"), env("DEV_STRESS_RUN_ID"),
    `--source-generation=${enrollment.sourceGeneration}`], { passthrough: false });
  const seed = seedOutput.split("\n").map((line) => {
    try { return JSON.parse(line); } catch { return null; }
  }).find((value) => value?.status === "accepted");
  if (!seed || seed.sourceGeneration !== enrollment.sourceGeneration ||
      !Number.isInteger(seed.expectedControlSequence) || seed.expectedControlSequence < 1) {
    throw new Error("immutable settlement control seed did not return an accepted frontier");
  }
  await waitForControlFinality(env("POSTMATCH_EVENT_STREAM"),
    seed.expectedControlSequence, enrollment.bindingDigest);
}

async function waitForControlFinality(stream, expectedSequence, bindingDigest) {
  if (!/^[A-Za-z0-9_-]+$/.test(stream)) throw new Error("invalid journal event stream");
  const deadline = Date.now() + 180_000;
  let observed = { journal: null, finality: null };
  while (Date.now() < deadline) {
    for (const [name, service, sql] of [
      ["journal", "settlement-postgres", `SELECT last_control_sequence, last_control_digest,
        next_batch_sequence - 1, last_batch_digest, incarnation_id
        FROM settlement.settlement_journal_heads WHERE event_stream = '${stream}'`],
      ["finality", "finality-postgres", `SELECT control_sequence, control_digest,
        acknowledged_batch_sequence, acknowledged_batch_digest, journal_incarnation_id,
        source_binding_digest FROM finality.settlement_finality_anchors
        WHERE event_stream = '${stream}'`],
    ]) {
      const { stdout } = await run("docker", composeArgs(["exec", "-T", service, "psql",
        "-X", "-A", "-t", "-F", "|", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef",
        "-c", sql]), { passthrough: false });
      const fields = stdout.trim().split("|");
      observed[name] = /^\d+$/.test(fields[0] ?? "") ? fields : null;
    }
    const journal = observed.journal;
    const finality = observed.finality;
    if (journal && finality && Number(journal[0]) === expectedSequence &&
        Number(finality[0]) === expectedSequence && journal[1] === finality[1] &&
        journal[2] === finality[2] && journal[3] === finality[3] &&
        journal[4] === finality[4] && finality[5] === bindingDigest) {
      console.log(`immutable controls finalized through sequence=${expectedSequence}`);
      return;
    }
    if (journal && finality && Number(journal[0]) === expectedSequence &&
        Number(finality[0]) === expectedSequence && journal[2] === finality[2]) {
      throw new Error(`settlement control finality proof differs from journal: ${JSON.stringify(observed)}`);
    }
    if (Number(journal?.[0] ?? 0) > expectedSequence ||
        Number(finality?.[0] ?? 0) > expectedSequence) {
      throw new Error(`settlement control frontier exceeded seed: ${JSON.stringify(observed)}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`settlement controls did not reach external finality: expected=${expectedSequence} observed=${JSON.stringify(observed)}`);
}

function configureCandidateReadProbe() {
  const fixture = readFileSync(env("DEV_STRESS_SESSION_CONFIG"), "utf8");
  const instrumentId = fixture.match(/^\s+instrumentId: ([A-Za-z0-9_-]+)\s*$/m)?.[1];
  if (!instrumentId) throw new Error("candidate read probe needs fixture instrument");
  const runId = env("DEV_STRESS_RUN_ID");
  const partitionId = streamRoutingPartition({ runId,
    venueSessionId: MATERIALIZER_STRESS_SESSION_ID, instrumentId,
    partitionCount: Number(env("STREAM_ACK_PARTITION_COUNT")) });
  const base = env("SETTLEMENT_CANDIDATE_CONTROL_RUNTIME_URL");
  const scope = new URLSearchParams({ partitionId: String(partitionId), runId,
    venueSessionId: MATERIALIZER_STRESS_SESSION_ID, instrumentId, currency: "USD",
    includeAge: "true" });
  const account = new URLSearchParams({ runId,
    participantId: "materializer-mm-01-participant", accountId: "materializer-mm-01-account",
    assetType: "CASH", assetId: "USD" });
  const status = new URLSearchParams({ runId, tradeId: "__TRADE_ID__" });
  const args = [
    "--url", `book=${base}/api/v1/postmatch-candidate/book?${scope}`,
    "--url", `depth=${base}/api/v1/postmatch-candidate/depth?${scope}`,
    "--url", `tape=${base}/api/v1/postmatch-candidate/tape?${scope}`,
    "--url", `balance=${base}/api/v1/postmatch-candidate/balance?${account}`,
    "--url", `status=${base}/api/v1/postmatch-candidate/status?${status}`,
    "--participant-id", "materializer-mm-01-participant",
    "--duration-seconds", "300", "--interval-ms", "1000",
    "--output", join(env("DEV_STRESS_ARTIFACT_DIR"), "postmatch-candidate-read-probe.json")];
  const p95 = env("POSTMATCH_CANDIDATE_READ_P95_MS");
  const p99 = env("POSTMATCH_CANDIDATE_READ_P99_MS");
  if (Boolean(p95) !== Boolean(p99)) throw new Error("both candidate read latency gates are required together");
  if (p95 && p99) args.push("--latency-p95-ms", p95, "--latency-p99-ms", p99);
  setValue("DEV_STRESS_CANDIDATE_READ_PROBE_ARGS_JSON", JSON.stringify(args));
}
await stopIdleBackgroundServices();
let stageSampler;
let stageSamplerExit;
if (env("REEF_DO_MATCHED_TOPOLOGY", "0") === "1") {
  stageSampler = spawn("node", ["scripts/dev/postmatch-stage-sampler.mjs",
    env("MATCHING_ENGINE_EVENT_STREAM"),
    join(env("DEV_STRESS_ARTIFACT_DIR"), "postmatch-stage-samples.jsonl"),
    env("REEF_DO_POSTMATCH_JOURNAL_DIAGNOSTIC", "0") === "1" ? "journal" : "true",
    "10000"],
  { stdio: "inherit", env: process.env });
  stageSamplerExit = new Promise((resolve) => stageSampler.once("exit", (code, signal) => resolve({ code, signal })));
}
try {
  await import("./stress.mjs");
} finally {
  if (stageSampler) {
    stageSampler.kill("SIGTERM");
    const result = await stageSamplerExit;
    if (result.code !== 0) throw new Error(`post-match stage sampler failed: ${JSON.stringify(result)}`);
  }
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
    if (hasProfile("benchmark-scale")) {
      services.push(...Array.from({ length: 12 }, (_, index) => `platform-projector-${index + 4}`));
    }
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
  const priceTickNanos = Number(process.env.DEV_STRESS_PRICE_TICK_NANOS || "10000000");
  if (!Number.isSafeInteger(priceTickNanos) || priceTickNanos <= 0) {
    throw new Error(`DEV_STRESS_PRICE_TICK_NANOS must be a positive safe integer, got ${priceTickNanos}`);
  }
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
      "      priceTickNanos: " + priceTickNanos,
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
