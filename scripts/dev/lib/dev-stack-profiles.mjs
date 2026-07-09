import { composeArgs } from "./compose-utils.mjs";
import { devReset, devUp } from "./dev-stack.mjs";
import { env, loadDotEnv, run, setDefault, setValue } from "./dev-utils.mjs";
import { printStreamProfileSummary, validateStreamProfile } from "./stream-profile-guard.mjs";

export const STACK_PROFILES = new Set([
  "default",
  "runtime-nodb",
  "captured-ack",
  "stream-ack",
  "stream-direct-nodb",
]);

export async function runStackUp(profile = "default") {
  loadDotEnv();
  const afterUp = applyStackProfile(profile);
  await devUp();
  for (const action of afterUp) {
    await action();
  }
}

export async function runStackDown() {
  loadDotEnv();
  const profiles = env("DEV_COMPOSE_PROFILES", "");
  if (profiles) {
    process.env.COMPOSE_PROFILES = profiles;
  }
  await run("docker", composeArgs(["down", "--remove-orphans"]));
}

export async function runStackReset() {
  loadDotEnv();
  await devReset();
}

export function applyStackProfile(profile = "default") {
  switch (profile) {
    case "default":
      return [];
    case "runtime-nodb":
      applyRuntimeNoDbProfile();
      return [];
    case "captured-ack":
      applyCapturedAckProfile();
      return [];
    case "stream-ack":
      applyStreamAckProfile();
      return [bootstrapCommandStream];
    case "stream-direct-nodb":
      applyStreamDirectNoDbProfile();
      applyStreamAckProfile();
      return [bootstrapCommandStream];
    default:
      throw new Error(`unknown stack profile: ${profile}`);
  }
}

function applyRuntimeNoDbProfile() {
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
}

function applyCapturedAckProfile() {
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
}

function applyStreamAckProfile() {
  setValue("EXTERNAL_API_COMMAND_PROCESSING_MODE", "stream-ack");
  setDefault("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
  setDefault("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
  setDefault("STREAM_ACK_LOG_PROVIDER", "jetstream");
  setDefault("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS");
  setDefault("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1");
  setDefault("STREAM_ACK_PARTITION_COUNT", "64");
  if (streamAckLogProvider() === "redpanda") {
    setDefault("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "redpanda:9092");
    setDefault("STREAM_ACK_KAFKA_PUBLISH_MAX_IN_FLIGHT", "16384");
    setDefault("STREAM_ACK_KAFKA_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION", "5");
    setDefault("STREAM_ACK_KAFKA_LINGER_MS", "1");
    setDefault("STREAM_ACK_KAFKA_BATCH_SIZE", "65536");
    setDefault("STREAM_ACK_KAFKA_COMPRESSION_TYPE", "lz4");
    setDefault("STREAM_ACK_KAFKA_BUFFER_MEMORY_BYTES", "67108864");
    setDefault("STREAM_ACK_KAFKA_MAX_BLOCK_MS", "250");
    setDefault("STREAM_ACK_KAFKA_REQUEST_TIMEOUT_MS", "1000");
    setDefault("STREAM_ACK_KAFKA_DELIVERY_TIMEOUT_MS", "30000");
  }
  setDefault("STREAM_ACK_INTAKE_STORE", "postgres");
  setDefault("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", "2000");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "false");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY", "4096");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE", "256");
  setDefault("STREAM_ACK_BACKPRESSURE_SAMPLE_MS", "100");
  setDefault("STREAM_ACK_MARK_PUBLISHED_MODE", "worker");
  setDefault("STREAM_ACK_MARK_PUBLISHED_WORKERS", "4");
  setDefault("STREAM_ACK_MARK_PUBLISHED_QUEUE_CAPACITY", "500000");
  setDefault("STREAM_ACK_WORKER_ENABLED", "true");
  setDefault("STREAM_ACK_WORKER_0_PARTITIONS", "0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15");
  setDefault("STREAM_ACK_WORKER_1_PARTITIONS", "16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31");
  setDefault("STREAM_ACK_WORKER_2_PARTITIONS", "32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47");
  setDefault("STREAM_ACK_WORKER_3_PARTITIONS", "48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63");
  setDefault("STREAM_ACK_WORKER_BATCH_SIZE", "1000");
  setDefault("STREAM_ACK_WORKER_POLL_MS", "10");
  setDefault("STREAM_ACK_WORKER_FETCH_TIMEOUT_MS", "200");
  setDefault("STREAM_ACK_WORKER_ACK_WAIT_MS", "60000");
  setDefault("STREAM_ACK_WORKER_MAX_ACK_PENDING", "4000");
  setDefault("STREAM_ACK_WORKER_DEDICATED_RUNTIME_POOL_ENABLED", "true");
  setDefault("STREAM_ACK_CANONICAL_EVENT_ROWS_ENABLED", "false");
  setDefault("STREAM_ACK_CANONICAL_QUERY_INDEXES_ENABLED", "false");
  setDefault("STREAM_ACK_MAX_WORKER_STREAM_LAG", "50000");
  setDefault("STREAM_ACK_MAX_PROJECTOR_LAG", "0");
  setDefault("STREAM_ACK_DRAIN_BACKPRESSURE_POLICY", "venue-core");
  setDefault("STREAM_ACK_DRAIN_BACKPRESSURE_SAMPLE_MS", "500");
  setDefault("STREAM_ACK_BACKPRESSURE_WORKER_DURABLES", streamAckWorkerDurables());
  setDefault("STREAM_ACK_PROJECTOR_ENABLED", "true");
  setDefault("STREAM_ACK_PROJECTOR_0_PARTITIONS", "0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15");
  setDefault("STREAM_ACK_PROJECTOR_1_PARTITIONS", "16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31");
  setDefault("STREAM_ACK_PROJECTOR_2_PARTITIONS", "32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47");
  setDefault("STREAM_ACK_PROJECTOR_3_PARTITIONS", "48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63");
  setDefault("STREAM_ACK_PROJECTOR_BATCH_SIZE", "2000");
  setDefault("STREAM_ACK_PROJECTOR_POLL_MS", "10");
  setDefault("RUNTIME_DB_POOL_STREAM_INTAKE_API_MAX", "64");
  setDefault("RUNTIME_DB_POOL_STREAM_INTAKE_API_MIN_IDLE", "16");
  setDefault("RUNTIME_DB_POOL_STREAM_INTAKE_BACKGROUND_MAX", "4");
  setDefault("RUNTIME_DB_POOL_STREAM_INTAKE_BACKGROUND_MIN_IDLE", "0");
  setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_MAX", "24");
  setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_MIN_IDLE", "8");
  setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MAX", "24");
  setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MIN_IDLE", "8");
  setValue("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), streamAckComposeProfiles()));

  console.log("stream-ack runtime settings:");
  console.log(`  processingMode=${env("EXTERNAL_API_COMMAND_PROCESSING_MODE")}`);
  console.log(`  logProvider=${streamAckLogProvider()}`);
  if (streamAckLogProvider() === "redpanda") {
    console.log(`  kafkaBootstrapServers=${env("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "redpanda:9092")}`);
    console.log(`  kafkaPublishMaxInFlight=${env("STREAM_ACK_KAFKA_PUBLISH_MAX_IN_FLIGHT")}`);
    console.log(`  kafkaMaxInFlightRequestsPerConnection=${env("STREAM_ACK_KAFKA_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION")}`);
    console.log(`  kafkaLingerMs=${env("STREAM_ACK_KAFKA_LINGER_MS")}`);
    console.log(`  kafkaBatchSize=${env("STREAM_ACK_KAFKA_BATCH_SIZE")}`);
    console.log(`  kafkaCompression=${env("STREAM_ACK_KAFKA_COMPRESSION_TYPE")}`);
  } else {
    console.log(`  natsUrl=${env("STREAM_ACK_NATS_URL", "nats://nats:4222")}`);
  }
  console.log(`  stream=${env("STREAM_ACK_COMMAND_STREAM")}`);
  console.log(`  subjectPrefix=${env("STREAM_ACK_SUBJECT_PREFIX")}`);
  console.log(`  partitions=${env("STREAM_ACK_PARTITION_COUNT")}`);
  console.log(`  intakeStore=${env("STREAM_ACK_INTAKE_STORE")}`);
  console.log(`  publishPipeline=${env("STREAM_ACK_PUBLISH_PIPELINE_ENABLED")}`);
  console.log(`  publishPipelineQueueCapacity=${env("STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY")}`);
  console.log(`  publishPipelineInFlightPerLane=${env("STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE")}`);
  console.log(`  workerEnabled=${env("STREAM_ACK_WORKER_ENABLED")}`);
  console.log(`  worker0Partitions=${env("STREAM_ACK_WORKER_0_PARTITIONS")}`);
  console.log(`  worker1Partitions=${env("STREAM_ACK_WORKER_1_PARTITIONS")}`);
  console.log(`  worker2Partitions=${env("STREAM_ACK_WORKER_2_PARTITIONS")}`);
  console.log(`  worker3Partitions=${env("STREAM_ACK_WORKER_3_PARTITIONS")}`);
  console.log(`  workerBatchSize=${env("STREAM_ACK_WORKER_BATCH_SIZE")}`);
  console.log(`  workerAckWaitMs=${env("STREAM_ACK_WORKER_ACK_WAIT_MS")}`);
  console.log(`  workerMaxAckPending=${env("STREAM_ACK_WORKER_MAX_ACK_PENDING")}`);
  console.log(`  canonicalEventRows=${env("STREAM_ACK_CANONICAL_EVENT_ROWS_ENABLED")}`);
  console.log(`  canonicalQueryIndexes=${env("STREAM_ACK_CANONICAL_QUERY_INDEXES_ENABLED")}`);
  console.log(`  projectorEnabled=${env("STREAM_ACK_PROJECTOR_ENABLED")}`);
  console.log(`  projector0Partitions=${env("STREAM_ACK_PROJECTOR_0_PARTITIONS")}`);
  console.log(`  projector1Partitions=${env("STREAM_ACK_PROJECTOR_1_PARTITIONS")}`);
  console.log(`  projector2Partitions=${env("STREAM_ACK_PROJECTOR_2_PARTITIONS")}`);
  console.log(`  projector3Partitions=${env("STREAM_ACK_PROJECTOR_3_PARTITIONS")}`);
  console.log(`  projectorBatchSize=${env("STREAM_ACK_PROJECTOR_BATCH_SIZE")}`);
  console.log(`  orderLifecycleProjectorEnabled=${env("ORDER_LIFECYCLE_PROJECTOR_ENABLED", "false")}`);
  console.log(`  marketDataProjectorEnabled=${env("MARKET_DATA_PROJECTOR_ENABLED", "false")}`);
  console.log(`  backpressureSampleMs=${env("STREAM_ACK_BACKPRESSURE_SAMPLE_MS")}`);
  console.log(`  drainBackpressureSampleMs=${env("STREAM_ACK_DRAIN_BACKPRESSURE_SAMPLE_MS")}`);
  console.log(`  drainBackpressurePolicy=${env("STREAM_ACK_DRAIN_BACKPRESSURE_POLICY")}`);
  console.log(`  maxWorkerStreamLag=${env("STREAM_ACK_MAX_WORKER_STREAM_LAG")}`);
  console.log(`  maxProjectorLag=${env("STREAM_ACK_MAX_PROJECTOR_LAG")}`);
  console.log(`  markPublishedMode=${env("STREAM_ACK_MARK_PUBLISHED_MODE")}`);
  console.log(`  streamIntakeApiPool=${env("RUNTIME_DB_POOL_STREAM_INTAKE_API_MIN_IDLE")}/${env("RUNTIME_DB_POOL_STREAM_INTAKE_API_MAX")}`);
  console.log(`  streamIntakeBackgroundPool=${env("RUNTIME_DB_POOL_STREAM_INTAKE_BACKGROUND_MIN_IDLE")}/${env("RUNTIME_DB_POOL_STREAM_INTAKE_BACKGROUND_MAX")}`);
  console.log(`  boundaryJdbcUrl=${env("RUNTIME_DB_URL", "jdbc:postgresql://boundary-postgres:5432/reef?currentSchema=boundary")}`);
  console.log(`  projectionJdbcUrl=${env("RUNTIME_PROJECTION_POSTGRES_JDBC_URL", "jdbc:postgresql://projection-postgres:5432/reef?currentSchema=runtime")}`);
}

function applyStreamDirectNoDbProfile() {
  setValue("RUNTIME_PERSISTENCE", "noop");
  setValue("EXTERNAL_API_IDEMPOTENCY_STORE", "inmemory");
  setValue("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
  setValue("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
  setValue("STREAM_ACK_INTAKE_STORE", "inmemory");
  setDefault("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES", "100000");
  setDefault("STREAM_ACK_INMEMORY_INTAKE_SHARDS", "256");
  setValue("PLATFORM_HTTP_SERVER", "netty");
  setDefault("STREAM_ACK_COMMAND_STREAM", "REEF_DIRECT_NODB_COMMANDS_V2");
  setDefault("STREAM_ACK_SUBJECT_PREFIX", "reef.direct.nodb.v2.cmd.v1");
  setDefault("STREAM_ACK_COMMAND_STREAM_MAX_BYTES", "34359738368");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "true");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY", "1024");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE", "256");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE", "1");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS", "0");
  setDefault("STREAM_INGRESS_ENABLED", "false");
  setDefault("STREAM_INGRESS_PORT", "8090");
  if (streamAckLogProvider() === "redpanda") {
    setDefault("STREAM_ACK_PARTITION_COUNT", "16");
    setDefault("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..15");
    setDefault("MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE", "none");
  } else {
    setDefault("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..63");
  }
  setValue("STREAM_ACK_WORKER_ENABLED", "false");
  if (env("DEV_STREAM_DIRECT_NODB_PROJECTOR_ENABLED", "0") === "1") {
    setValue("STREAM_ACK_PROJECTOR_ENABLED", "true");
  } else {
    setValue("STREAM_ACK_PROJECTOR_ENABLED", "false");
  }
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
  console.log(`  publisher=${env("STREAM_ACK_PUBLISHER", "stream") || "stream"}`);
  if (streamAckLogProvider() === "redpanda") {
    console.log(`  kafkaBootstrapServers=${env("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "redpanda:9092")}`);
    console.log(`  kafkaLingerMs=${env("STREAM_ACK_KAFKA_LINGER_MS", "1")}`);
    console.log(`  kafkaBatchSize=${env("STREAM_ACK_KAFKA_BATCH_SIZE", "65536")}`);
    console.log(`  kafkaCompression=${env("STREAM_ACK_KAFKA_COMPRESSION_TYPE", "lz4")}`);
    console.log(`  engineKafkaCompression=${env("MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE", "") || env("STREAM_ACK_KAFKA_COMPRESSION_TYPE", "lz4")}`);
  }
  console.log(`  runtimePersistence=${env("RUNTIME_PERSISTENCE")}`);
  console.log(`  idempotencyStore=${env("EXTERNAL_API_IDEMPOTENCY_STORE")}`);
  console.log(`  streamIntakeStore=${env("STREAM_ACK_INTAKE_STORE")}`);
  console.log(`  streamIntakeMaxEntries=${env("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES")}`);
  console.log(`  streamIntakeShards=${env("STREAM_ACK_INMEMORY_INTAKE_SHARDS")}`);
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
  validateStreamProfile(env("STREAM_ACK_PUBLISHER", "").trim().toLowerCase() === "noop" ? "noop-ceiling" : "stream-direct-nodb");
  printStreamProfileSummary(env("STREAM_ACK_PUBLISHER", "").trim().toLowerCase() === "noop" ? "noop-ceiling" : "stream-direct-nodb");
}

async function bootstrapCommandStream() {
  if (streamAckLogProvider() === "redpanda") {
    console.log(`Redpanda/Kafka stream ${env("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS")} will be created by the runtime producer if needed.`);
    return;
  }
  const stream = env("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS");
  const subjectPrefix = env("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1");
  const subjects = `${subjectPrefix}.>`;
  const maxBytes = env("STREAM_ACK_COMMAND_STREAM_MAX_BYTES", "1073741824");

  console.log(`bootstrapping JetStream stream ${stream} (${subjects})...`);
  if (await streamExists(stream)) {
    console.log(`JetStream stream ${stream} already exists; leaving existing stream configuration in place.`);
    return;
  }
  try {
    await run("docker", composeArgs([
      "run",
      "-T",
      "--rm",
      "nats-box",
      "nats",
      "--server",
      "nats://nats:4222",
      "stream",
      "add",
      stream,
      "--subjects",
      subjects,
      "--storage",
      "file",
      "--retention",
      "limits",
      "--discard",
      "new",
      "--max-bytes",
      maxBytes,
      "--dupe-window",
      env("STREAM_ACK_COMMAND_STREAM_DUPE_WINDOW", "2m"),
      "--defaults",
    ]));
  } catch (error) {
    if (await streamExists(stream)) {
      console.warn(`JetStream stream ${stream} exists after create returned nonzero; continuing.`);
      return;
    }
    throw error;
  }
}

async function streamExists(stream) {
  try {
    await run(
      "docker",
      composeArgs([
        "run",
        "-T",
        "--rm",
        "nats-box",
        "nats",
        "--server",
        "nats://nats:4222",
        "stream",
        "info",
        stream,
        "--json",
      ]),
      { passthrough: false },
    );
    return true;
  } catch (_error) {
    return false;
  }
}

function streamAckLogProvider() {
  const raw = env("STREAM_ACK_LOG_PROVIDER", "jetstream").trim().toLowerCase();
  if (raw === "redpanda" || raw === "kafka") return "redpanda";
  return "jetstream";
}

function streamAckComposeProfiles() {
  const profiles = ["stream-ack"];
  if (streamAckLogProvider() === "redpanda") {
    profiles.push("redpanda");
  }
  return profiles;
}

function streamAckWorkerDurables() {
  const prefix = env("STREAM_ACK_WORKER_DURABLE_PREFIX", "reef-stream-worker");
  return [0, 1, 2, 3]
    .flatMap((workerIndex) =>
      durablesForWorker(
        `${prefix}-w${workerIndex}`,
        env(`STREAM_ACK_WORKER_${workerIndex}_PARTITIONS`, ""),
      ),
    )
    .join(",");
}

function durablesForWorker(prefix, rawPartitions) {
  return parsePartitions(rawPartitions).map((partition) => `${prefix}-${partitionToken(partition)}`);
}

function parsePartitions(raw) {
  return String(raw)
    .split(",")
    .map((value) => Number.parseInt(value.trim(), 10))
    .filter((value) => Number.isInteger(value) && value >= 0);
}

function partitionToken(partition) {
  const partitionCount = Number.parseInt(env("STREAM_ACK_PARTITION_COUNT", "64"), 10);
  const width = Math.max(2, String(partitionCount - 1).length);
  return `p${String(partition).padStart(width, "0")}`;
}

function appendProfiles(raw, additions) {
  const profiles = new Set(
    String(raw ?? "")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean),
  );
  for (const addition of additions) {
    profiles.add(addition);
  }
  return [...profiles].join(",");
}

function parsePositiveInt(value, fallback) {
  const parsed = Number.parseInt(String(value ?? ""), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
