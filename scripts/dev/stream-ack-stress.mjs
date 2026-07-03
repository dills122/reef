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

await import("./stress.mjs");

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}
