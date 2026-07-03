import "./captured-ack-up.mjs";

setDefault("DEV_STRESS_MODE", "capacity-baseline");
setDefault("DEV_STRESS_PROFILE", "capacity-heavy");
setDefault("DEV_STRESS_RATES", "2500,3500");
setDefault("DEV_STRESS_SWEEP_WORKERS", "128");
setDefault("DEV_STRESS_TRACE_CHECK_LIMIT", "200");
setDefault("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "0");
setDefault("DEV_STRESS_FAIL_ON_ACCOUNTING_GAP", "1");
setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-captured-ack-stress");
setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-captured-ack-stress/captured-ack-stress.json");

await import("./stress.mjs");

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}
