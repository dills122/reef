import { env, loadDotEnv, setDefault, setValue } from "./lib/dev-utils.mjs";

loadDotEnv();

const projectionName = env("DEV_PROJECTION_FRESHNESS_PROJECTION_NAME", "runtime-normalized-venue-outcomes");
const marketDataProjectionName = env("DEV_PROJECTION_FRESHNESS_MARKET_DATA_PROJECTION_NAME", "market-data-top-of-book");

setValue("STREAM_ACK_PROJECTOR_ENABLED", "true");
setValue("STREAM_ACK_PROJECTION_SOURCE", "venue-event-batch");
setValue("STREAM_ACK_PROJECTOR_INCLUDE_FILLS", "true");
setValue("STREAM_ACK_PROJECTION_NAME", projectionName);
setValue("ORDER_LIFECYCLE_PROJECTOR_ENABLED", "true");
setValue("MARKET_DATA_PROJECTOR_ENABLED", "true");
setValue("MARKET_DATA_PROJECTOR_PROJECTION_NAME", marketDataProjectionName);
setValue("MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME", projectionName);

setValue("DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR", "1");
setValue("DEV_STRESS_FAIL_ON_STREAM_ACK_PROJECTOR_FAILURES", "1");
setValue("DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_FAILED_DELTA", "0");
setValue("DEV_STRESS_MAX_STREAM_ACK_PROJECTOR_LAG", "0");
setValue("DEV_STRESS_MAX_STREAM_ACK_PROJECTION_GAP", "0");
setDefault("DEV_STRESS_STREAM_ACK_PROJECTOR_DRAIN_WAIT_MS", "60000");
setDefault("DEV_STRESS_STREAM_ACK_PROJECTOR_DRAIN_POLL_MS", "1000");

setDefault("DEV_STRESS_RUN_PROFILE", "materializer-projection-freshness");
setDefault("DEV_STRESS_RATES", "2500");
setDefault("DEV_STRESS_SWEEP_WORKERS", "256");
setDefault("DEV_STRESS_DURATION", "60s");
setDefault("DEV_STRESS_ARTIFACT_DIR", "/tmp/reef-projection-freshness-gate");
setDefault("DEV_STRESS_REPORT_OUT", "/tmp/reef-projection-freshness-gate/projection-freshness-gate.json");
setDefault("DEV_STRESS_SCENARIO_ID", "projection-freshness:venue-event-materializer");

console.log("projection freshness gate settings:");
console.log(`  projectionName=${projectionName}`);
console.log(`  marketDataProjectionName=${marketDataProjectionName}`);
console.log(`  rates=${process.env.DEV_STRESS_RATES}`);
console.log(`  workers=${process.env.DEV_STRESS_SWEEP_WORKERS}`);
console.log(`  duration=${process.env.DEV_STRESS_DURATION}`);
console.log(`  projectorDrainWaitMs=${process.env.DEV_STRESS_STREAM_ACK_PROJECTOR_DRAIN_WAIT_MS}`);
console.log(`  artifactDir=${process.env.DEV_STRESS_ARTIFACT_DIR}`);

await import("./venue-event-materializer-stress.mjs");
