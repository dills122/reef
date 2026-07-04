import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

setValue("RUNTIME_PERSISTENCE", "noop");
setValue("EXTERNAL_API_IDEMPOTENCY_STORE", "inmemory");
setValue("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
setValue("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
setValue("STREAM_ACK_INTAKE_STORE", "inmemory");
setValue("PLATFORM_HTTP_SERVER", "netty");
setDefault("STREAM_ACK_COMMAND_STREAM", "REEF_DIRECT_NODB_COMMANDS_V2");
setDefault("STREAM_ACK_SUBJECT_PREFIX", "reef.direct.nodb.v2.cmd.v1");
setDefault("STREAM_ACK_COMMAND_STREAM_MAX_BYTES", "34359738368");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "true");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY", "8192");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE", "256");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE", "1");
setDefault("STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS", "0");
setDefault("STREAM_INGRESS_ENABLED", "false");
setDefault("STREAM_INGRESS_PORT", "8090");
if (streamAckLogProvider() === "redpanda") {
  setDefault("STREAM_ACK_PARTITION_COUNT", "16");
  setDefault("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..15");
} else {
  setDefault("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..63");
}
setValue("STREAM_ACK_WORKER_ENABLED", "false");
setValue("STREAM_ACK_PROJECTOR_ENABLED", "false");
setValue("MATCHING_ENGINE_DIRECT_STREAM_ENABLED", "true");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE", "500");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_FETCH_TIMEOUT_MS", "100");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_POLL_MS", "1");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING", "16000");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_ACK_WAIT_MS", "60000");
setDefault("MATCHING_ENGINE_EVENT_STREAM", "REEF_DIRECT_NODB_VENUE_EVENTS_V2");
setDefault("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", "reef.direct.nodb.v2.venue.events.v1");
setValue("EXTERNAL_API_ABUSE_BREAKER_MODE", "off");

console.log("stream-direct no-db settings:");
console.log(`  logProvider=${env("STREAM_ACK_LOG_PROVIDER", "jetstream")}`);
if (streamAckLogProvider() === "redpanda") {
  console.log(`  kafkaBootstrapServers=${env("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "redpanda:9092")}`);
  console.log(`  kafkaLingerMs=${env("STREAM_ACK_KAFKA_LINGER_MS", "1")}`);
  console.log(`  kafkaBatchSize=${env("STREAM_ACK_KAFKA_BATCH_SIZE", "65536")}`);
  console.log(`  kafkaCompression=${env("STREAM_ACK_KAFKA_COMPRESSION_TYPE", "lz4")}`);
}
console.log(`  runtimePersistence=${env("RUNTIME_PERSISTENCE")}`);
console.log(`  idempotencyStore=${env("EXTERNAL_API_IDEMPOTENCY_STORE")}`);
console.log(`  streamIntakeStore=${env("STREAM_ACK_INTAKE_STORE")}`);
console.log(`  stream=${env("STREAM_ACK_COMMAND_STREAM")}`);
console.log(`  subjectPrefix=${env("STREAM_ACK_SUBJECT_PREFIX")}`);
console.log(`  streamMaxBytes=${env("STREAM_ACK_COMMAND_STREAM_MAX_BYTES")}`);
console.log(`  publishMode=${env("STREAM_ACK_PUBLISH_MODE", "sync")}`);
console.log(`  publishMaxInFlight=${env("STREAM_ACK_PUBLISH_MAX_IN_FLIGHT", "4096")}`);
console.log(`  publishPipeline=${env("STREAM_ACK_PUBLISH_PIPELINE_ENABLED")}`);
console.log(`  publishPipelineQueueCapacity=${env("STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY")}`);
console.log(`  publishPipelineInFlightPerLane=${env("STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE")}`);
console.log(`  publishPipelineBatchSize=${env("STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE")}`);
console.log(`  publishPipelineBatchLingerMs=${env("STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS")}`);
console.log(`  streamIngress=${env("STREAM_INGRESS_ENABLED")}`);
console.log(`  streamIngressPort=${env("STREAM_INGRESS_PORT")}`);
console.log(`  httpServer=${env("PLATFORM_HTTP_SERVER")}`);
console.log(`  streamWorker=${env("STREAM_ACK_WORKER_ENABLED")}`);
console.log(`  streamProjector=${env("STREAM_ACK_PROJECTOR_ENABLED")}`);
console.log(`  engineDirect=${env("MATCHING_ENGINE_DIRECT_STREAM_ENABLED")}`);
console.log(`  engineDirectPartitions=${env("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS")}`);
console.log(`  engineDirectBatchSize=${env("MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE")}`);
console.log(`  engineDirectMaxAckPending=${env("MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING")}`);

await import("./stream-ack-up.mjs");

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}

function setValue(name, value) {
  process.env[name] = value;
}

function streamAckLogProvider() {
  const raw = env("STREAM_ACK_LOG_PROVIDER", "jetstream").trim().toLowerCase();
  if (raw === "redpanda" || raw === "kafka") return "redpanda";
  return "jetstream";
}
