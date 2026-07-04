import { devUp } from "./lib/dev-stack.mjs";
import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();

setValue("EXTERNAL_API_COMMAND_PROCESSING_MODE", "stream-ack");
setDefault("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
setDefault("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
setDefault("STREAM_ACK_LOG_PROVIDER", "jetstream");
setDefault("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS");
setDefault("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1");
setDefault("STREAM_ACK_PARTITION_COUNT", "64");
if (streamAckLogProvider() === "redpanda") {
  setDefault("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "redpanda:9092");
}
setDefault("STREAM_ACK_INTAKE_STORE", "postgres");
setDefault("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", "2000");
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
setDefault("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), streamAckComposeProfiles()));

console.log("stream-ack runtime settings:");
console.log(`  processingMode=${env("EXTERNAL_API_COMMAND_PROCESSING_MODE")}`);
console.log(`  logProvider=${streamAckLogProvider()}`);
if (streamAckLogProvider() === "redpanda") {
  console.log(`  kafkaBootstrapServers=${env("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "redpanda:9092")}`);
} else {
  console.log(`  natsUrl=${env("STREAM_ACK_NATS_URL", "nats://nats:4222")}`);
}
console.log(`  stream=${env("STREAM_ACK_COMMAND_STREAM")}`);
console.log(`  subjectPrefix=${env("STREAM_ACK_SUBJECT_PREFIX")}`);
console.log(`  partitions=${env("STREAM_ACK_PARTITION_COUNT")}`);
console.log(`  intakeStore=${env("STREAM_ACK_INTAKE_STORE")}`);
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

await devUp();
await bootstrapCommandStream();

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
    await run("docker", [
      "compose",
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
    ]);
  } catch (error) {
    if (await streamExists(stream)) {
      console.warn(`JetStream stream ${stream} exists after create returned nonzero; continuing.`);
      return;
    }
    throw error;
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

async function streamExists(stream) {
  try {
    await run(
      "docker",
      [
        "compose",
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
      ],
      { passthrough: false },
    );
    return true;
  } catch (_error) {
    return false;
  }
}

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}

function setValue(name, value) {
  process.env[name] = value;
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
