import { devUp } from "./lib/dev-stack.mjs";
import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();

setValue("EXTERNAL_API_COMMAND_PROCESSING_MODE", "stream-ack");
setDefault("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
setDefault("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
setDefault("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS");
setDefault("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1");
setDefault("STREAM_ACK_PARTITION_COUNT", "16");
setDefault("STREAM_ACK_INTAKE_STORE", "postgres");
setDefault("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", "2000");
setDefault("STREAM_ACK_BACKPRESSURE_SAMPLE_MS", "100");
setDefault("STREAM_ACK_WORKER_ENABLED", "true");
setDefault("STREAM_ACK_WORKER_0_PARTITIONS", "0,1,2,3,4,5,6,7");
setDefault("STREAM_ACK_WORKER_1_PARTITIONS", "8,9,10,11,12,13,14,15");
setDefault("STREAM_ACK_WORKER_BATCH_SIZE", "250");
setDefault("STREAM_ACK_WORKER_POLL_MS", "10");
setDefault("STREAM_ACK_WORKER_FETCH_TIMEOUT_MS", "200");
setDefault("STREAM_ACK_WORKER_ACK_WAIT_MS", "30000");
setDefault("STREAM_ACK_WORKER_DEDICATED_RUNTIME_POOL_ENABLED", "true");
setDefault("STREAM_ACK_PROJECTOR_ENABLED", "true");
setDefault("STREAM_ACK_PROJECTOR_0_PARTITIONS", "0,1,2,3,4,5,6,7");
setDefault("STREAM_ACK_PROJECTOR_1_PARTITIONS", "8,9,10,11,12,13,14,15");
setDefault("RUNTIME_DB_POOL_STREAM_INTAKE_MAX", "32");
setDefault("RUNTIME_DB_POOL_STREAM_INTAKE_MIN_IDLE", "8");
setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_MAX", "24");
setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_MIN_IDLE", "8");
setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MAX", "24");
setDefault("RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MIN_IDLE", "8");
setDefault("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), ["stream-ack"]));

console.log("stream-ack runtime settings:");
console.log(`  processingMode=${env("EXTERNAL_API_COMMAND_PROCESSING_MODE")}`);
console.log(`  natsUrl=${env("STREAM_ACK_NATS_URL", "nats://nats:4222")}`);
console.log(`  stream=${env("STREAM_ACK_COMMAND_STREAM")}`);
console.log(`  subjectPrefix=${env("STREAM_ACK_SUBJECT_PREFIX")}`);
console.log(`  partitions=${env("STREAM_ACK_PARTITION_COUNT")}`);
console.log(`  intakeStore=${env("STREAM_ACK_INTAKE_STORE")}`);
console.log(`  workerEnabled=${env("STREAM_ACK_WORKER_ENABLED")}`);
console.log(`  worker0Partitions=${env("STREAM_ACK_WORKER_0_PARTITIONS")}`);
console.log(`  worker1Partitions=${env("STREAM_ACK_WORKER_1_PARTITIONS")}`);
console.log(`  workerBatchSize=${env("STREAM_ACK_WORKER_BATCH_SIZE")}`);
console.log(`  projectorEnabled=${env("STREAM_ACK_PROJECTOR_ENABLED")}`);
console.log(`  projector0Partitions=${env("STREAM_ACK_PROJECTOR_0_PARTITIONS")}`);
console.log(`  projector1Partitions=${env("STREAM_ACK_PROJECTOR_1_PARTITIONS")}`);
console.log(`  backpressureSampleMs=${env("STREAM_ACK_BACKPRESSURE_SAMPLE_MS")}`);
console.log(`  boundaryJdbcUrl=${env("RUNTIME_DB_URL", "jdbc:postgresql://boundary-postgres:5432/reef?currentSchema=boundary")}`);
console.log(`  projectionJdbcUrl=${env("RUNTIME_PROJECTION_POSTGRES_JDBC_URL", "jdbc:postgresql://projection-postgres:5432/reef?currentSchema=runtime")}`);

await devUp();
await bootstrapCommandStream();

async function bootstrapCommandStream() {
  const stream = env("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS");
  const subjectPrefix = env("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1");
  const subjects = `${subjectPrefix}.>`;
  const maxBytes = env("STREAM_ACK_COMMAND_STREAM_MAX_BYTES", "1073741824");

  console.log(`bootstrapping JetStream stream ${stream} (${subjects})...`);
  if (await streamExists(stream)) {
    console.log(`JetStream stream ${stream} already exists; leaving existing stream configuration in place.`);
    return;
  }
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
