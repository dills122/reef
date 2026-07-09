import { devUp } from "./lib/dev-stack.mjs";
import { env, loadDotEnv, setDefault, setValue } from "./lib/dev-utils.mjs";

loadDotEnv();

setValue("RUNTIME_PERSISTENCE", "noop");
setValue("EXTERNAL_API_IDEMPOTENCY_STORE", "inmemory");
setValue("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
setValue("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
setDefault("EXTERNAL_API_COMMAND_PROCESSING_MODE", "sync-result");
setValue("EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED", "false");
setValue("STREAM_ACK_INTAKE_STORE", "inmemory");
setValue("STREAM_ACK_WORKER_ENABLED", "false");
setValue("STREAM_ACK_PROJECTOR_ENABLED", "false");
setValue("EXTERNAL_API_ABUSE_BREAKER_MODE", "off");

console.log("runtime no-db benchmark settings:");
console.log(`  runtimePersistence=${env("RUNTIME_PERSISTENCE")}`);
console.log(`  idempotencyStore=${env("EXTERNAL_API_IDEMPOTENCY_STORE")}`);
console.log(`  commandCapture=${env("EXTERNAL_API_COMMAND_CAPTURE_MODE")}`);
console.log(`  commandLog=${env("EXTERNAL_API_COMMAND_LOG_MODE")}`);
console.log(`  processingMode=${env("EXTERNAL_API_COMMAND_PROCESSING_MODE")}`);
console.log(`  httpServer=${env("PLATFORM_HTTP_SERVER", "jdk")}`);
console.log(`  streamIntakeStore=${env("STREAM_ACK_INTAKE_STORE")}`);
console.log(`  streamWorker=${env("STREAM_ACK_WORKER_ENABLED")}`);
console.log(`  streamProjector=${env("STREAM_ACK_PROJECTOR_ENABLED")}`);

await devUp();
