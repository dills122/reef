import { env, loadDotEnv } from "./lib/dev-utils.mjs";
import { printStreamProfileSummary, streamProfileNames, validateStreamProfile } from "./lib/stream-profile-guard.mjs";

loadDotEnv();

const profile = process.argv[2] || env("DEV_STREAM_PROFILE", "stream-direct-nodb");
if (profile === "--help" || profile === "-h") {
  console.log(`usage: node scripts/dev/stream-profile-validate.mjs [${streamProfileNames.join("|")}]`);
  process.exit(0);
}

applyProfileDefaults(profile);
validateStreamProfile(profile);
printStreamProfileSummary(profile);
console.log(`stream profile ${profile} ok`);

function applyProfileDefaults(profileName) {
  setDefault("RUNTIME_PERSISTENCE", "noop");
  setDefault("EXTERNAL_API_IDEMPOTENCY_STORE", "inmemory");
  setDefault("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
  setDefault("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
  setDefault("STREAM_ACK_INTAKE_STORE", "inmemory");
  setDefault("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES", "100000");
  setDefault("STREAM_ACK_INMEMORY_INTAKE_SHARDS", "256");
  setDefault("PLATFORM_HTTP_SERVER", "netty");
  setDefault("MATCHING_ENGINE_DIRECT_STREAM_ENABLED", "true");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "true");
  setDefault("STREAM_ACK_WORKER_ENABLED", "false");
  setDefault("STREAM_ACK_PROJECTOR_ENABLED", "false");
  setDefault("EXTERNAL_API_ABUSE_BREAKER_MODE", "off");

  if (profileName === "noop-ceiling") {
    setDefault("STREAM_ACK_PUBLISHER", "noop");
  }

  if (profileName === "materializer-soak") {
    setDefault("STREAM_ACK_LOG_PROVIDER", "redpanda");
    setDefault("VENUE_EVENT_MATERIALIZER_ENABLED", "true");
    setDefault("DEV_STRESS_CAPTURE_STREAM_DIRECT", "1");
    setDefault("DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER", "1");
    setDefault("DEV_STRESS_FAIL_ON_STREAM_DIRECT_FAILURES", "1");
    setDefault("DEV_STRESS_FAIL_ON_VENUE_EVENT_MATERIALIZER_FAILURES", "1");
    setDefault("DEV_STRESS_MAX_STREAM_DIRECT_COMPLETION_GAP", "0");
    setDefault("DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_COMPLETION_GAP", "0");
    setDefault("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", "1000");
    setDefault("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", "250000");
  }
}

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}
