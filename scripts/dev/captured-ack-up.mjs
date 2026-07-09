import { devUp } from "./lib/dev-stack.mjs";
import { env, loadDotEnv, setDefault, setValue } from "./lib/dev-utils.mjs";

loadDotEnv();

setValue("EXTERNAL_API_COMMAND_LOG_MODE", "postgres");
setValue("EXTERNAL_API_COMMAND_PROCESSING_MODE", "captured-ack");
setDefault("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
setDefault("EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE", "side-table");
setValue("EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED", "true");
setDefault("EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS", "4");
setDefault("EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE", "250");
setDefault("EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS", "5");
setDefault("EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS", "60000");
setDefault("EXTERNAL_API_COMMAND_ASYNC_WORKER_DEDICATED_RUNTIME_POOL_ENABLED", "false");
setDefault(
  "EXTERNAL_API_COMMAND_INTAKE_MAX_ACTIVE_COMMANDS",
  String(
    Math.max(
      1,
      parsePositiveInt(env("EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS"), 4) *
        parsePositiveInt(env("EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE"), 250) *
        2,
    ),
  ),
);
setDefault("EXTERNAL_API_COMMAND_INTAKE_BACKPRESSURE_SAMPLE_MS", "100");

console.log("captured-ack runtime settings:");
console.log(`  commandLog=${env("EXTERNAL_API_COMMAND_LOG_MODE")}`);
console.log(`  commandLogPayload=${env("EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE")}`);
console.log(`  legacyCommandCapture=${env("EXTERNAL_API_COMMAND_CAPTURE_MODE")}`);
console.log(`  processingMode=${env("EXTERNAL_API_COMMAND_PROCESSING_MODE")}`);
console.log(`  asyncWorker=${env("EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED")}`);
console.log(`  asyncThreads=${env("EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS")}`);
console.log(`  asyncBatchSize=${env("EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE")}`);
console.log(`  asyncPollMs=${env("EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS")}`);
console.log(`  asyncLeaseMs=${env("EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS")}`);
console.log(`  asyncDedicatedRuntimePool=${env("EXTERNAL_API_COMMAND_ASYNC_WORKER_DEDICATED_RUNTIME_POOL_ENABLED")}`);
console.log(`  intakeMaxActive=${env("EXTERNAL_API_COMMAND_INTAKE_MAX_ACTIVE_COMMANDS")}`);
console.log(`  intakeBackpressureSampleMs=${env("EXTERNAL_API_COMMAND_INTAKE_BACKPRESSURE_SAMPLE_MS")}`);

await devUp();

function parsePositiveInt(value, fallback) {
  const parsed = Number.parseInt(String(value ?? ""), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
