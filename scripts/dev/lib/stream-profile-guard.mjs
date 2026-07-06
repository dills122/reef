import { env } from "./dev-utils.mjs";

export const streamProfileNames = [
  "stream-direct-nodb",
  "noop-ceiling",
  "materializer-soak",
];

export function validateStreamProfile(profileName, options = {}) {
  const profile = String(profileName || "stream-direct-nodb").trim();
  const issues = [];
  const warnings = [];

  if (!streamProfileNames.includes(profile)) {
    issues.push(`unknown stream profile '${profile}'`);
    return report(profile, issues, warnings, options);
  }

  expect("RUNTIME_PERSISTENCE", "noop", issues);
  expect("EXTERNAL_API_IDEMPOTENCY_STORE", "inmemory", issues);
  expect("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled", issues);
  expect("EXTERNAL_API_COMMAND_LOG_MODE", "disabled", issues);
  expect("STREAM_ACK_INTAKE_STORE", "inmemory", issues);
  expect("PLATFORM_HTTP_SERVER", "netty", issues);
  expect("MATCHING_ENGINE_DIRECT_STREAM_ENABLED", "true", issues);
  expect("STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "true", issues);
  expect("EXTERNAL_API_ABUSE_BREAKER_MODE", "off", issues);
  expectPositiveInt("STREAM_ACK_INMEMORY_INTAKE_SHARDS", issues);

  const maxEntries = Number(env("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES", "0"));
  if (!Number.isFinite(maxEntries) || maxEntries < 0) {
    issues.push("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES must be integer >= 0");
  } else if (maxEntries === 0 && env("DEV_ALLOW_UNBOUNDED_STREAM_INTAKE", "0") !== "1") {
    issues.push("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES must be > 0 for soak profiles");
  }

  const publisher = env("STREAM_ACK_PUBLISHER", "stream").trim().toLowerCase() || "stream";
  if (profile === "noop-ceiling") {
    expect("STREAM_ACK_PUBLISHER", "noop", issues);
    warnings.push("noop-ceiling is API-front-door isolation only; 202 does not prove broker append");
  } else if (publisher === "noop") {
    issues.push(`${profile} must not use STREAM_ACK_PUBLISHER=noop`);
  }

  if (profile === "materializer-soak") {
    expect("STREAM_ACK_LOG_PROVIDER", "redpanda", issues);
    expect("STREAM_ACK_WORKER_ENABLED", "false", issues);
    expect("VENUE_EVENT_MATERIALIZER_ENABLED", "true", issues);
    expect("DEV_STRESS_CAPTURE_STREAM_DIRECT", "1", issues);
    expect("DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER", "1", issues);
    expect("DEV_STRESS_FAIL_ON_VENUE_EVENT_MATERIALIZER_FAILURES", "1", issues);
    expectPositiveInt("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", issues);
    expectPositiveInt("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", issues);
  } else {
    expect("STREAM_ACK_WORKER_ENABLED", "false", issues);
    expect("STREAM_ACK_PROJECTOR_ENABLED", "false", issues);
  }

  return report(profile, issues, warnings, options);
}

export function printStreamProfileSummary(profileName) {
  const profile = String(profileName || "stream-direct-nodb").trim();
  const keys = [
    "STREAM_ACK_LOG_PROVIDER",
    "STREAM_ACK_PUBLISHER",
    "STREAM_ACK_INTAKE_STORE",
    "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES",
    "STREAM_ACK_INMEMORY_INTAKE_SHARDS",
    "STREAM_ACK_PUBLISH_PIPELINE_ENABLED",
    "STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY",
    "STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE",
    "STREAM_ACK_COMMAND_STREAM",
    "STREAM_ACK_SUBJECT_PREFIX",
    "MATCHING_ENGINE_DIRECT_STREAM_ENABLED",
    "MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS",
    "MATCHING_ENGINE_EVENT_STREAM",
    "VENUE_EVENT_MATERIALIZER_ENABLED",
  ];
  console.log(`stream profile ${profile} validated settings:`);
  for (const key of keys) {
    if (process.env[key] != null && process.env[key] !== "") {
      console.log(`  ${key}=${process.env[key]}`);
    }
  }
}

function expect(name, expected, issues) {
  const actual = env(name, "");
  if (actual !== expected) {
    issues.push(`${name} expected '${expected}' got '${actual}'`);
  }
}

function expectPositiveInt(name, issues) {
  const value = Number(env(name, "0"));
  if (!Number.isInteger(value) || value <= 0) {
    issues.push(`${name} must be integer > 0`);
  }
}

function report(profile, issues, warnings, options) {
  if (options.printWarnings !== false) {
    for (const warning of warnings) {
      console.warn(`stream profile warning: ${warning}`);
    }
  }
  if (issues.length > 0) {
    const message = [`stream profile ${profile} invalid:`, ...issues.map((issue) => `- ${issue}`)].join("\n");
    if (options.throwOnError === false) {
      return { ok: false, profile, issues, warnings };
    }
    throw new Error(message);
  }
  return { ok: true, profile, issues, warnings };
}
