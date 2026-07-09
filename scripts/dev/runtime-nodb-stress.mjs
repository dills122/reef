import { setDefault } from "./lib/dev-utils.mjs";
import "./runtime-nodb-up.mjs";

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
console.log(`  rates=${process.env.DEV_STRESS_RATES}`);
console.log(`  workers=${process.env.DEV_STRESS_SWEEP_WORKERS || process.env.DEV_STRESS_WORKERS}`);
console.log(`  duration=${process.env.DEV_STRESS_DURATION}`);
console.log(`  rateSchedule=${process.env.DEV_STRESS_RATE_SCHEDULE}`);
console.log(`  rateQueueDepth=${process.env.DEV_STRESS_RATE_QUEUE_DEPTH}`);
console.log(`  profile=${process.env.DEV_STRESS_PROFILE}`);
console.log(`  traceCheckLimit=${process.env.DEV_STRESS_TRACE_CHECK_LIMIT}`);
console.log(`  artifactDir=${process.env.DEV_STRESS_ARTIFACT_DIR}`);

await import("./stress.mjs");
