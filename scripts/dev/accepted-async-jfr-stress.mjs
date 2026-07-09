import { mkdirSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";

import { composeArgs } from "./lib/compose-utils.mjs";
import { env, loadDotEnv, run, setDefault, setValue } from "./lib/dev-utils.mjs";
import { runStressProfile } from "./lib/dev-stress-profiles.mjs";

loadDotEnv();

const artifactDir = env("DEV_JFR_ARTIFACT_DIR", "/tmp/reef-accepted-async-jfr");
const containerJfrPath = env("DEV_JFR_CONTAINER_PATH", "/tmp/reef-platform-api-accepted-async.jfr");
const localJfrPath = join(artifactDir, basename(containerJfrPath));
mkdirSync(artifactDir, { recursive: true });

const isolatedCompose = env("DEV_JFR_ISOLATED_COMPOSE", "1") !== "0";
if (isolatedCompose) {
  configureIsolatedCompose();
}

setValue("EXTERNAL_API_COMMAND_PROCESSING_MODE", "accepted-async");
setValue("PLATFORM_HTTP_SERVER", "netty");
setValue("ENGINE_TRANSPORT", "grpc-stream");
setDefault("EXTERNAL_API_ACCEPTED_ASYNC_LANES", "16");
setDefault("EXTERNAL_API_ACCEPTED_ASYNC_QUEUE_CAPACITY", "100000");
setDefault("EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE", "32");
setDefault("EXTERNAL_API_ACCEPTED_ASYNC_TERMINAL_STATUS_MAX_RECORDS", "100000");

setDefault("DEV_STRESS_RATES", "5000,10000,15000");
setDefault("DEV_STRESS_SWEEP_WORKERS", "512");
setDefault("DEV_STRESS_DURATION", "30s");
setDefault("DEV_STRESS_ARTIFACT_DIR", artifactDir);
setDefault("DEV_STRESS_REPORT_OUT", join(artifactDir, "accepted-async-jfr-stress.json"));
setDefault("DEV_STRESS_SCENARIO_ID", "accepted-async:jfr-submit");
setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "0");

process.env.JAVA_TOOL_OPTIONS = javaToolOptionsWithJfr({
  existing: env("JAVA_TOOL_OPTIONS", "-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"),
  name: env("DEV_JFR_RECORDING_NAME", "reef-accepted-async"),
  settings: env("DEV_JFR_SETTINGS", "profile"),
  maxSize: env("DEV_JFR_MAX_SIZE", "512m"),
  path: containerJfrPath,
});

console.log("accepted-async JFR stress settings:");
console.log(`  artifactDir=${artifactDir}`);
console.log(`  containerJfrPath=${containerJfrPath}`);
console.log(`  localJfrPath=${localJfrPath}`);
console.log(`  rates=${process.env.DEV_STRESS_RATES}`);
console.log(`  workers=${process.env.DEV_STRESS_SWEEP_WORKERS}`);
console.log(`  duration=${process.env.DEV_STRESS_DURATION}`);
console.log(`  acceptedAsyncInFlightPerLane=${process.env.EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE}`);
console.log(`  isolatedCompose=${isolatedCompose}`);
if (isolatedCompose) {
  console.log(`  composeProject=${process.env.COMPOSE_PROJECT_NAME}`);
  console.log(`  runtimePort=${process.env.REEF_PLATFORM_API_HOST_PORT}`);
  console.log(`  enginePort=${process.env.REEF_MATCHING_ENGINE_HOST_PORT}`);
}

let stressError = null;
try {
  await runStressProfile("runtime-nodb");
} catch (error) {
  stressError = error;
} finally {
  await bestEffort("stop platform-api for JFR dump", () => run("docker", composeArgs(["stop", "platform-api"])));
  await bestEffort("copy platform-api JFR artifact", () =>
    run("docker", composeArgs(["cp", `platform-api:${containerJfrPath}`, localJfrPath])),
  );
}

console.log(`JFR artifact: ${localJfrPath}`);

if (stressError) {
  throw stressError;
}

function javaToolOptionsWithJfr({ existing, name, settings, maxSize, path }) {
  if (existing.includes("-XX:StartFlightRecording")) {
    throw new Error("JAVA_TOOL_OPTIONS already contains -XX:StartFlightRecording; use DEV_JFR_* knobs with this wrapper");
  }
  const jfrSettings = [
    `name=${name}`,
    `settings=${settings}`,
    `filename=${path}`,
    "disk=true",
    "dumponexit=true",
    `maxsize=${maxSize}`,
  ].join(",");
  return `${existing} -XX:StartFlightRecording=${jfrSettings}`.trim();
}

async function bestEffort(label, action) {
  try {
    await action();
  } catch (error) {
    console.warn(`${label} failed: ${error.message ?? error}`);
  }
}

function configureIsolatedCompose() {
  const suffix = sanitizeContainerSuffix(env("DEV_JFR_STACK_SUFFIX", "jfr"));
  const composeOverridePath = join(artifactDir, `compose.accepted-async-${suffix}.yml`);
  writeFileSync(composeOverridePath, isolatedComposeOverride(suffix));

  setDefault("COMPOSE_PROJECT_NAME", `reef-${suffix}`);
  setValue("DEV_COMPOSE_FILES", composeFilesWithOverride(composeOverridePath));

  setValue("REEF_POSTGRES_HOST_PORT", env("DEV_JFR_POSTGRES_HOST_PORT", "15432"));
  setValue("REEF_PROJECTION_POSTGRES_HOST_PORT", env("DEV_JFR_PROJECTION_POSTGRES_HOST_PORT", "15433"));
  setValue("REEF_BOUNDARY_POSTGRES_HOST_PORT", env("DEV_JFR_BOUNDARY_POSTGRES_HOST_PORT", "15434"));
  setValue("REEF_ARENA_POSTGRES_HOST_PORT", env("DEV_JFR_ARENA_POSTGRES_HOST_PORT", "15435"));
  setValue("REEF_NATS_HOST_PORT", env("DEV_JFR_NATS_HOST_PORT", "14222"));
  setValue("REEF_NATS_MONITOR_HOST_PORT", env("DEV_JFR_NATS_MONITOR_HOST_PORT", "18222"));
  setValue("REEF_MATCHING_ENGINE_HOST_PORT", env("DEV_JFR_MATCHING_ENGINE_HOST_PORT", "18081"));
  setValue("REEF_MATCHING_ENGINE_GRPC_HOST_PORT", env("DEV_JFR_MATCHING_ENGINE_GRPC_HOST_PORT", "19081"));
  setValue("REEF_PLATFORM_API_HOST_PORT", env("DEV_JFR_PLATFORM_API_HOST_PORT", "18080"));
  setValue("REEF_STREAM_INGRESS_HOST_PORT", env("DEV_JFR_STREAM_INGRESS_HOST_PORT", "18090"));
  setValue("REEF_PLATFORM_WORKER_0_HOST_PORT", env("DEV_JFR_PLATFORM_WORKER_0_HOST_PORT", "18082"));
  setValue("REEF_PLATFORM_WORKER_1_HOST_PORT", env("DEV_JFR_PLATFORM_WORKER_1_HOST_PORT", "18083"));
  setValue("REEF_PLATFORM_WORKER_2_HOST_PORT", env("DEV_JFR_PLATFORM_WORKER_2_HOST_PORT", "18086"));
  setValue("REEF_PLATFORM_WORKER_3_HOST_PORT", env("DEV_JFR_PLATFORM_WORKER_3_HOST_PORT", "18087"));
  setValue("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", env("DEV_JFR_PLATFORM_PROJECTOR_0_HOST_PORT", "18084"));
  setValue("REEF_PLATFORM_PROJECTOR_1_HOST_PORT", env("DEV_JFR_PLATFORM_PROJECTOR_1_HOST_PORT", "18085"));
  setValue("REEF_PLATFORM_PROJECTOR_2_HOST_PORT", env("DEV_JFR_PLATFORM_PROJECTOR_2_HOST_PORT", "18088"));
  setValue("REEF_PLATFORM_PROJECTOR_3_HOST_PORT", env("DEV_JFR_PLATFORM_PROJECTOR_3_HOST_PORT", "18089"));
  setValue("REEF_PLATFORM_MATERIALIZER_HOST_PORT", env("DEV_JFR_PLATFORM_MATERIALIZER_HOST_PORT", "18091"));
  setValue("REEF_PLATFORM_MATERIALIZER_1_HOST_PORT", env("DEV_JFR_PLATFORM_MATERIALIZER_1_HOST_PORT", "18092"));
  setValue("REEF_PLATFORM_MATERIALIZER_2_HOST_PORT", env("DEV_JFR_PLATFORM_MATERIALIZER_2_HOST_PORT", "18093"));
  setValue("REEF_PLATFORM_MATERIALIZER_3_HOST_PORT", env("DEV_JFR_PLATFORM_MATERIALIZER_3_HOST_PORT", "18094"));
}

function composeFilesWithOverride(composeOverridePath) {
  const existing = env("DEV_COMPOSE_FILES", env("REEF_COMPOSE_FILES", "compose.base.yml,compose.local.yml"));
  return [existing, composeOverridePath].filter(Boolean).join(",");
}

function sanitizeContainerSuffix(value) {
  const sanitized = value.toLowerCase().replace(/[^a-z0-9_.-]+/g, "-").replace(/^-+|-+$/g, "");
  if (!sanitized) {
    throw new Error("DEV_JFR_STACK_SUFFIX must contain at least one alphanumeric character");
  }
  return sanitized;
}

function isolatedComposeOverride(suffix) {
  const services = [
    "postgres",
    "projection-postgres",
    "boundary-postgres",
    "arena-postgres",
    "redis",
    "nats",
    "redpanda",
    "jaeger",
    "otel-collector",
    "matching-engine",
    "platform-api",
    "platform-worker-0",
    "platform-worker-1",
    "platform-worker-2",
    "platform-worker-3",
    "platform-projector-0",
    "platform-projector-1",
    "platform-projector-2",
    "platform-projector-3",
    "platform-materializer",
    "platform-materializer-1",
    "platform-materializer-2",
    "platform-materializer-3",
  ];

  const lines = ["services:"];
  for (const service of services) {
    lines.push(`  ${service}:`);
    lines.push(`    container_name: reef-${suffix}-${service}`);
  }
  lines.push("");
  return lines.join("\n");
}
