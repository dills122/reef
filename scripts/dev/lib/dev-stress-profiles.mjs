import { loadDotEnv, setDefault } from "./dev-utils.mjs";
import { runStackUp } from "./dev-stack-profiles.mjs";
import { setDefaultGeneratedSpreadSubmitSessionConfig } from "./stress-session-config.mjs";

export const STRESS_PROFILES = new Set([
  "runtime-nodb",
  "captured-ack",
  "stream-ack",
  "stream-direct-nodb",
]);

export async function runStressProfile(profile) {
  loadDotEnv();
  applyStressProfile(profile);
  await runStackUp(stackProfileForStress(profile));
  await import("../stress.mjs");
}

export function applyStressProfile(profile) {
  switch (profile) {
    case "runtime-nodb":
      applyRuntimeNoDbStressProfile();
      return;
    case "captured-ack":
      applyCapturedAckStressProfile();
      return;
    case "stream-ack":
      applyStreamAckStressProfile();
      return;
    case "stream-direct-nodb":
      applyStreamDirectNoDbStressProfile();
      return;
    default:
      throw new Error(`unknown stress profile: ${profile}`);
  }
}

function stackProfileForStress(profile) {
  return profile;
}

function applyRuntimeNoDbStressProfile() {
  setDefault("DEV_STRESS_MODE", "strict-lifecycle");
  setDefault("DEV_STRESS_PROFILE", "stream-submit");
  setDefault("DEV_STRESS_RATES", "1000,2500,5000,7500,10000");
  setDefault("DEV_STRESS_SWEEP_WORKERS", "256");
  setDefault("DEV_STRESS_DURATION", "30s");
  setDefault("DEV_STRESS_RATE_SCHEDULE", "precise");
  setDefault("DEV_STRESS_RATE_QUEUE_DEPTH", "200000");
  setDefault("DEV_STRESS_TRACE_CHECK_LIMIT", "0");
  setDefault("DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING", "0");
  setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS", "0");
  setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR", "0");
  setDefault("DEV_STRESS_CAPTURE_DB_DIAGNOSTICS", "0");
  setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "95");
  setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-runtime-nodb-stress");
  setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-runtime-nodb-stress/runtime-nodb-stress.json");
  setDefault("DEV_STRESS_SCENARIO_ID", "runtime-nodb:stream-submit");

  console.log("runtime no-db stress settings:");
  printCommonStressSettings();
  console.log(`  profile=${process.env.DEV_STRESS_PROFILE}`);
  console.log(`  traceCheckLimit=${process.env.DEV_STRESS_TRACE_CHECK_LIMIT}`);
  console.log(`  artifactDir=${process.env.DEV_STRESS_ARTIFACT_DIR}`);
}

function applyCapturedAckStressProfile() {
  setDefault("DEV_STRESS_MODE", "capacity-baseline");
  setDefault("DEV_STRESS_PROFILE", "capacity-heavy");
  setDefault("DEV_STRESS_RATES", "2500,3500");
  setDefault("DEV_STRESS_SWEEP_WORKERS", "128");
  setDefault("DEV_STRESS_TRACE_CHECK_LIMIT", "200");
  setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "0");
  setDefault("DEV_STRESS_FAIL_ON_ACCOUNTING_GAP", "1");
  setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-captured-ack-stress");
  setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-captured-ack-stress/captured-ack-stress.json");
}

function applyStreamAckStressProfile() {
  setDefault("PLATFORM_INTERNAL_HTTP_MODE", "enabled");
  setDefault("DEV_STRESS_MODE", "strict-lifecycle");
  setDefault("DEV_STRESS_PROFILE", "stream-submit");
  setDefault("DEV_STRESS_RATES", "1000,2500,5000");
  setDefault("DEV_STRESS_SWEEP_WORKERS", "256");
  setDefault("DEV_STRESS_TRACE_CHECK_LIMIT", "200");
  setDefault("DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING", "0");
  setDefault("DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS", "1");
  setDefault("DEV_STRESS_STREAM_ACK_DRAIN_WAIT_MS", "15000");
  setDefault("DEV_STRESS_STREAM_ACK_DRAIN_POLL_MS", "1000");
  setDefault("DEV_STRESS_STREAM_ACK_WORKER_PROBE_TIMEOUT_MS", "15000");
  setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "90");
  setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-stream-ack-stress");
  setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-stream-ack-stress/stream-ack-stress.json");
  setDefaultGeneratedSpreadSubmitSessionConfig({
    artifactDir: "/tmp/reef-stream-ack-stress",
    fileName: "stream-ack-submit-spread.yaml",
    instrumentCountEnv: "DEV_STRESS_STREAM_ACK_INSTRUMENTS",
    sessionName: "stream-ack-submit-stress",
    scenarioRunId: "stream-ack-submit-stress",
    seed: 515151,
    duration: "30s",
    traceCheckLimit: 200,
  });
}

function applyStreamDirectNoDbStressProfile() {
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
  setDefaultGeneratedSpreadSubmitSessionConfig({
    artifactDir: "/tmp/reef-stream-direct-nodb-stress",
    fileName: "stream-direct-submit-spread.yaml",
    instrumentCountEnv: "DEV_STRESS_STREAM_DIRECT_INSTRUMENTS",
    sessionName: "stream-direct-nodb-submit-stress",
    scenarioRunId: "stream-direct-nodb-submit-stress",
    seed: 616161,
    duration: "90s",
    traceCheckLimit: 0,
  });

  console.log("stream-direct no-db stress settings:");
  printCommonStressSettings();
  console.log(`  rateSchedule=${process.env.DEV_STRESS_RATE_SCHEDULE}`);
  console.log(`  rateQueueDepth=${process.env.DEV_STRESS_RATE_QUEUE_DEPTH}`);
  console.log(`  transport=${process.env.DEV_STRESS_TRANSPORT || process.env.REEF_TRANSPORT || "http"}`);
  console.log(`  streamAddress=${process.env.DEV_STRESS_STREAM_ADDRESS || process.env.REEF_STREAM_ADDRESS || "127.0.0.1:8090"}`);
  console.log(`  directDrainWaitMs=${process.env.DEV_STRESS_STREAM_DIRECT_DRAIN_WAIT_MS}`);
  console.log(`  artifactDir=${process.env.DEV_STRESS_ARTIFACT_DIR}`);
}

function printCommonStressSettings() {
  console.log(`  rates=${process.env.DEV_STRESS_RATES}`);
  console.log(`  workers=${process.env.DEV_STRESS_SWEEP_WORKERS || process.env.DEV_STRESS_WORKERS}`);
  console.log(`  duration=${process.env.DEV_STRESS_DURATION}`);
}
