import { mkdirSync } from "node:fs";
import { basename, join } from "node:path";

import { composeArgs } from "./lib/compose-utils.mjs";
import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();

const artifactDir = env("DEV_JFR_ARTIFACT_DIR", "/tmp/reef-accepted-async-jfr");
const containerJfrPath = env("DEV_JFR_CONTAINER_PATH", "/tmp/reef-platform-api-accepted-async.jfr");
const localJfrPath = join(artifactDir, basename(containerJfrPath));
mkdirSync(artifactDir, { recursive: true });

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

let stressError = null;
try {
  await import("./runtime-nodb-stress.mjs");
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

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}

function setValue(name, value) {
  process.env[name] = value;
}
