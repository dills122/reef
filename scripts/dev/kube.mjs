import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { env, loadDotEnv, sleep } from "./lib/dev-utils.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");
const manifestsRoot = path.join(repoRoot, "infra/local-kube/k8s");

loadDotEnv();

const clusterName = env("KUBE_LOCAL_CLUSTER", "reef-local");

const config = {
  clusterName,
  namespace: env("KUBE_NAMESPACE", "reef-local"),
  context: env("KUBE_CONTEXT", `k3d-${clusterName}`),
  runtimeImage: env("REEF_PLATFORM_RUNTIME_IMAGE", "reef-platform-runtime:local-kube"),
  engineImage: env("REEF_MATCHING_ENGINE_IMAGE", "reef-matching-engine:local-kube"),
  buildImages: env("KUBE_BUILD_IMAGES", "1") !== "0",
  importImages: env("KUBE_IMPORT_IMAGES", "1") !== "0",
  waitTimeoutSeconds: Number(env("KUBE_WAIT_TIMEOUT_SECONDS", "300")),
  runtimeHostPort: env("REEF_PLATFORM_API_HOST_PORT", "8080"),
  engineHostPort: env("REEF_MATCHING_ENGINE_HOST_PORT", "8081"),
};

if (!/^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$/.test(config.namespace)) {
  throw new Error(`KUBE_NAMESPACE must be a valid Kubernetes namespace, got ${config.namespace}`);
}

const command = process.argv[2] ?? "help";

switch (command) {
  case "up":
    await up();
    break;
  case "apply":
    await apply();
    break;
  case "reset":
    await down();
    await up();
    break;
  case "materializer-up":
    await materializerUp();
    break;
  case "materializer-smoke":
    await materializerSmoke();
    break;
  case "materializer-scale":
    await materializerScale();
    break;
  case "stream-ack-up":
    await streamAckUp();
    break;
  case "stream-ack-smoke":
    await streamAckSmoke();
    break;
  case "autoscale-apply":
    await autoscaleApply();
    break;
  case "build-images":
    await buildImages();
    break;
  case "import-images":
    await importImages();
    break;
  case "migrate":
    await migrate();
    break;
  case "port-forward":
    await portForward();
    break;
  case "smoke":
    await smoke();
    break;
  case "status":
    await status();
    break;
  case "render-manifest":
    process.stdout.write(renderManifest(process.argv[3] ?? "00-namespace.yaml"));
    break;
  case "down":
    await down();
    break;
  case "help":
  case "--help":
  case "-h":
    printHelp();
    break;
  default:
    console.error(`unknown kube command: ${command}`);
    printHelp();
    process.exit(1);
}

async function up() {
  await ensureCluster();
  if (config.buildImages) {
    await buildImages();
  }
  if (config.importImages) {
    await importImages();
  }
  await apply();
}

async function apply() {
  assertManifests();
  await applyManifest("00-namespace.yaml", { contextOptional: true });
  await applyManifest("10-postgres.yaml");
  await applyManifest("20-nats.yaml");
  await waitStatefulSets(["postgres", "projection-postgres", "boundary-postgres", "arena-postgres", "nats"]);
  await migrate();
  await applyManifest("30-matching-engine.yaml");
  await applyManifest("40-platform-api.yaml");
  await kubectl(["set", "image", "deployment/matching-engine", `matching-engine=${config.engineImage}`]);
  await kubectl(["set", "image", "deployment/platform-api", `platform-api=${config.runtimeImage}`]);
  await waitDeployments(["matching-engine", "platform-api"]);
}

async function materializerUp() {
  await up();
  await applyManifest("50-redpanda.yaml");
  await waitStatefulSets(["redpanda"]);
  await configureMaterializerProfile();
  await applyManifest("60-materializer-profile.yaml");
  await kubectl(["set", "image", "deployment/matching-engine", `matching-engine=${config.engineImage}`]);
  await kubectl(["set", "image", "deployment/platform-api", `platform-api=${config.runtimeImage}`]);
  await kubectl(["set", "image", "deployment/platform-materializer", `platform-materializer=${config.runtimeImage}`]);
  await kubectl(["set", "image", "deployment/platform-projector-0", `platform-projector-0=${config.runtimeImage}`]);
  await kubectl(["set", "image", "deployment/platform-read-api", `platform-read-api=${config.runtimeImage}`]);
  await waitDeployments(["matching-engine", "platform-api", "platform-materializer", "platform-projector-0", "platform-read-api"]);
}

async function materializerScale() {
  await materializerUp();
  const replicas = Number(env("KUBE_MATERIALIZER_REPLICAS", "4"));
  if (!Number.isInteger(replicas) || replicas < 1) {
    throw new Error(`KUBE_MATERIALIZER_REPLICAS must be a positive integer, got ${env("KUBE_MATERIALIZER_REPLICAS")}`);
  }
  await kubectl(["scale", "deployment/platform-materializer", `--replicas=${replicas}`]);
  await waitDeployments(["platform-materializer"]);
}

async function streamAckUp() {
  await up();
  await applyManifest("50-redpanda.yaml");
  await waitStatefulSets(["redpanda"]);
  await configureStreamAckProfile();
  await applyManifest("70-stream-ack-profile.yaml");
  for (const deployment of [
    "platform-worker-0",
    "platform-worker-1",
    "platform-worker-2",
    "platform-worker-3",
    "platform-projector-0",
    "platform-projector-1",
    "platform-projector-2",
    "platform-projector-3",
  ]) {
    await kubectl(["set", "image", `deployment/${deployment}`, `${deployment}=${config.runtimeImage}`]);
  }
  await waitDeployments([
    "matching-engine",
    "platform-api",
    "platform-worker-0",
    "platform-worker-1",
    "platform-worker-2",
    "platform-worker-3",
    "platform-projector-0",
    "platform-projector-1",
    "platform-projector-2",
    "platform-projector-3",
  ]);
}

async function streamAckSmoke() {
  await streamAckUp();
  const forwards = await startPortForwards([
    ["svc/platform-api", config.runtimeHostPort, 8080],
    ["svc/matching-engine", config.engineHostPort, 8081],
  ]);
  const smokeId = env("DEV_KUBE_STREAM_ACK_SMOKE_ID", `kube-stream-ack-smoke-${Date.now()}`);
  try {
    await sleep(1500);
    await run(env("JS_RUNTIME", "bun"), ["scripts/dev/kube-stream-ack-smoke.mjs"], {
      env: {
        DEV_KUBE_STREAM_ACK_SMOKE_ID: smokeId,
        KUBE_NAMESPACE: config.namespace,
        KUBE_CONTEXT: config.context,
        RUNTIME_BASE_URL: `http://127.0.0.1:${config.runtimeHostPort}`,
        ENGINE_BASE_URL: `http://127.0.0.1:${config.engineHostPort}`,
      },
    });
  } finally {
    stopProcesses(forwards);
  }
}

async function configureMaterializerProfile() {
  await kubectl([
    "set",
    "env",
    "deployment/matching-engine",
    "STREAM_ACK_LOG_PROVIDER=redpanda",
    "STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092",
    "STREAM_ACK_KAFKA_COMPRESSION_TYPE=lz4",
    "MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE=none",
    "STREAM_ACK_COMMAND_STREAM=REEF_KUBE_MATERIALIZER_COMMANDS",
    "STREAM_ACK_SUBJECT_PREFIX=reef.kube.materializer.cmd.v1",
    "STREAM_ACK_PARTITION_COUNT=4",
    "MATCHING_ENGINE_DIRECT_STREAM_ENABLED=true",
    "MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS=0..3",
    "MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE=500",
    "MATCHING_ENGINE_DIRECT_STREAM_FETCH_TIMEOUT_MS=100",
    "MATCHING_ENGINE_DIRECT_STREAM_POLL_MS=1",
    "MATCHING_ENGINE_DIRECT_STREAM_ACK_WAIT_MS=60000",
    "MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING=16000",
    "MATCHING_ENGINE_EVENT_STREAM=REEF_KUBE_MATERIALIZER_VENUE_EVENTS",
    "MATCHING_ENGINE_EVENT_SUBJECT_PREFIX=reef.kube.materializer.venue.events.v1",
  ]);
  await kubectl([
    "set",
    "env",
    "deployment/platform-api",
    "EXTERNAL_API_COMMAND_PROCESSING_MODE=stream-ack",
    "RUNTIME_PERSISTENCE=noop",
    "EXTERNAL_API_IDEMPOTENCY_STORE=inmemory",
    "EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled",
    "EXTERNAL_API_COMMAND_LOG_MODE=disabled",
    "STREAM_ACK_INTAKE_STORE=inmemory",
    "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES=100000",
    "STREAM_ACK_INMEMORY_INTAKE_SHARDS=256",
    "PLATFORM_HTTP_SERVER=netty",
    "STREAM_ACK_LOG_PROVIDER=redpanda",
    "STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092",
    "STREAM_ACK_KAFKA_COMPRESSION_TYPE=lz4",
    "STREAM_ACK_COMMAND_STREAM=REEF_KUBE_MATERIALIZER_COMMANDS",
    "STREAM_ACK_SUBJECT_PREFIX=reef.kube.materializer.cmd.v1",
    "STREAM_ACK_PARTITION_COUNT=4",
    "STREAM_ACK_PUBLISH_PIPELINE_ENABLED=true",
    "STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY=1024",
    "STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE=256",
    "STREAM_ACK_WORKER_ENABLED=false",
    "STREAM_ACK_PROJECTOR_ENABLED=false",
    "MATCHING_ENGINE_DIRECT_STREAM_ENABLED=true",
    "EXTERNAL_API_ABUSE_BREAKER_MODE=off",
  ]);
  await waitDeployments(["matching-engine", "platform-api"]);
}

async function configureStreamAckProfile() {
  await kubectl([
    "set",
    "env",
    "deployment/platform-api",
    "EXTERNAL_API_COMMAND_PROCESSING_MODE=stream-ack",
    "EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled",
    "EXTERNAL_API_COMMAND_LOG_MODE=disabled",
    "STREAM_ACK_LOG_PROVIDER=redpanda",
    "STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092",
    "STREAM_ACK_KAFKA_COMPRESSION_TYPE=lz4",
    "STREAM_ACK_COMMAND_STREAM=REEF_KUBE_STREAM_ACK_COMMANDS",
    "STREAM_ACK_SUBJECT_PREFIX=reef.kube.stream.cmd.v1",
    "STREAM_ACK_PARTITION_COUNT=64",
    "STREAM_ACK_INTAKE_STORE=postgres",
    "STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS=2000",
    "STREAM_ACK_PUBLISH_PIPELINE_ENABLED=false",
    "STREAM_ACK_BACKPRESSURE_SAMPLE_MS=100",
    "STREAM_ACK_MARK_PUBLISHED_MODE=worker",
    "STREAM_ACK_MARK_PUBLISHED_WORKERS=4",
    "STREAM_ACK_MARK_PUBLISHED_QUEUE_CAPACITY=500000",
    `STREAM_ACK_BACKPRESSURE_WORKER_DURABLES=${streamAckWorkerDurables()}`,
    "STREAM_ACK_MAX_WORKER_STREAM_LAG=50000",
    "STREAM_ACK_MAX_PROJECTOR_LAG=0",
    "STREAM_ACK_DRAIN_BACKPRESSURE_POLICY=venue-core",
    "STREAM_ACK_DRAIN_BACKPRESSURE_SAMPLE_MS=500",
    "STREAM_ACK_WORKER_ENABLED=false",
    "STREAM_ACK_PROJECTOR_ENABLED=false",
    "RUNTIME_DB_POOL_STREAM_INTAKE_MAX=64",
    "RUNTIME_DB_POOL_STREAM_INTAKE_MIN_IDLE=16",
  ]);
  await waitDeployments(["platform-api"]);
}

async function autoscaleApply() {
  await applyManifest("80-autoscaling.yaml");
  await kubectl(["get", "hpa", "-o", "wide"]);
}

async function buildImages() {
  await run("docker", [
    "build",
    "-t",
    config.engineImage,
    "services/matching-engine",
  ]);
  await run("docker", [
    "build",
    "-t",
    config.runtimeImage,
    "services/platform-runtime",
  ]);
}

async function importImages() {
  await run("k3d", ["image", "import", config.engineImage, "-c", config.clusterName]);
  await run("k3d", ["image", "import", config.runtimeImage, "-c", config.clusterName]);
}

async function migrate() {
  await run(env("JS_RUNTIME", "bun"), ["scripts/dev/db/migrate.mjs"], {
    env: {
      REEF_MIGRATION_RUNNER: "kubectl",
      KUBE_NAMESPACE: config.namespace,
      KUBE_CONTEXT: config.context,
    },
  });
}

async function smoke() {
  const forwards = await startPortForwards();
  try {
    await sleep(1500);
    await run(env("JS_RUNTIME", "bun"), ["scripts/dev/smoke.mjs"], {
      env: {
        RUNTIME_BASE_URL: `http://127.0.0.1:${config.runtimeHostPort}`,
        ENGINE_BASE_URL: `http://127.0.0.1:${config.engineHostPort}`,
      },
    });
  } finally {
    stopProcesses(forwards);
  }
}

async function materializerSmoke() {
  await materializerUp();
  const forwards = await startPortForwards([
    ["svc/platform-api", config.runtimeHostPort, 8080],
    ["svc/matching-engine", config.engineHostPort, 8081],
    ["svc/platform-materializer", env("REEF_PLATFORM_MATERIALIZER_HOST_PORT", "8091"), 8080],
    ["svc/platform-read-api", env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", "8084"), 8080],
  ]);
  const smokeId = env("DEV_VENUE_EVENT_MATERIALIZER_SMOKE_ID", "kube-materializer-smoke");
  try {
    await sleep(1500);
    await run(env("JS_RUNTIME", "bun"), ["scripts/dev/venue-event-materializer-smoke.mjs"], {
      env: {
        DEV_VENUE_EVENT_MATERIALIZER_SKIP_STACK_UP: "1",
        DEV_VENUE_EVENT_MATERIALIZER_PSQL_RUNNER: "kubectl",
        KUBE_NAMESPACE: config.namespace,
        KUBE_CONTEXT: config.context,
        RUNTIME_BASE_URL: `http://127.0.0.1:${config.runtimeHostPort}`,
        ENGINE_BASE_URL: `http://127.0.0.1:${config.engineHostPort}`,
        VENUE_EVENT_MATERIALIZER_BASE_URL: `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_HOST_PORT", "8091")}`,
        DEV_VENUE_EVENT_MATERIALIZER_READ_API_URL: `http://127.0.0.1:${env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", "8084")}`,
        DEV_VENUE_EVENT_MATERIALIZER_SMOKE_ID: smokeId,
        STREAM_ACK_LOG_PROVIDER: "redpanda",
        STREAM_ACK_COMMAND_STREAM: "REEF_KUBE_MATERIALIZER_COMMANDS",
        STREAM_ACK_SUBJECT_PREFIX: "reef.kube.materializer.cmd.v1",
        STREAM_ACK_PARTITION_COUNT: "4",
        MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS: "0..3",
        MATCHING_ENGINE_EVENT_STREAM: "REEF_KUBE_MATERIALIZER_VENUE_EVENTS",
        MATCHING_ENGINE_EVENT_SUBJECT_PREFIX: "reef.kube.materializer.venue.events.v1",
        VENUE_EVENT_MATERIALIZER_TOPIC: "REEF_KUBE_MATERIALIZER_VENUE_EVENTS",
        VENUE_EVENT_MATERIALIZER_GROUP_ID: "reef-kube-venue-event-materializer",
        DEV_VENUE_EVENT_MATERIALIZER_PROJECTION_NAME: "runtime-normalized-venue-outcomes-kube",
        DEV_VENUE_EVENT_MATERIALIZER_MARKET_DATA_PROJECTION_NAME: "market-data-top-of-book-kube",
        DEV_VENUE_EVENT_MATERIALIZER_DEPTH_PROJECTION_NAME: "market-data-depth-kube",
      },
    });
  } finally {
    stopProcesses(forwards);
  }
}

async function portForward() {
  const forwards = await startPortForwards();
  console.log(`platform-api: http://127.0.0.1:${config.runtimeHostPort}`);
  console.log(`matching-engine: http://127.0.0.1:${config.engineHostPort}`);
  console.log("press Ctrl-C to stop port-forwards");
  await waitForSignal(forwards);
}

async function status() {
  await kubectl(["get", "pods,svc,pvc", "-o", "wide"]);
}

async function down() {
  await run("k3d", ["cluster", "delete", config.clusterName]);
}

async function ensureCluster() {
  const result = await runCapture("k3d", ["cluster", "list", config.clusterName]);
  if (result.code === 0 && result.stdout.includes(config.clusterName)) {
    return;
  }
  await run("k3d", [
    "cluster",
    "create",
    config.clusterName,
    "--wait",
    "--timeout",
    `${config.waitTimeoutSeconds}s`,
    "--no-lb",
    "--k3s-arg",
    "--disable=traefik@server:0",
    "--k3s-arg",
    "--disable=servicelb@server:0",
  ]);
}

async function waitStatefulSets(names) {
  for (const name of names) {
    await kubectl([
      "rollout",
      "status",
      `statefulset/${name}`,
      `--timeout=${config.waitTimeoutSeconds}s`,
    ]);
  }
}

async function waitDeployments(names) {
  for (const name of names) {
    await kubectl([
      "rollout",
      "status",
      `deployment/${name}`,
      `--timeout=${config.waitTimeoutSeconds}s`,
    ]);
  }
}

async function startPortForwards(bindings = [
  ["svc/platform-api", config.runtimeHostPort, 8080],
  ["svc/matching-engine", config.engineHostPort, 8081],
]) {
  await waitDeployments(["matching-engine", "platform-api"]);
  return bindings.map(([resource, hostPort, targetPort]) =>
    spawnKubectl([
      "port-forward",
      resource,
      `${hostPort}:${targetPort}`,
    ]),
  );
}

function spawnKubectl(args) {
  const child = spawn("kubectl", kubectlArgs(args), {
    cwd: repoRoot,
    stdio: "inherit",
    env: process.env,
  });
  child.on("error", (error) => {
    console.error(error?.message ?? error);
  });
  return child;
}

function kubectl(args, options = {}) {
  return run("kubectl", kubectlArgs(args, options));
}

function applyManifest(name, options = {}) {
  return run("kubectl", kubectlArgs(["apply", "-f", "-"], options), { input: renderManifest(name) });
}

function renderManifest(name) {
  const file = manifest(path.basename(name));
  if (!existsSync(file)) {
    throw new Error(`missing local kube manifest: ${file}`);
  }
  return readFileSync(file, "utf8")
    .replace(/^(\s*namespace:\s*)reef-local\s*$/gm, `$1${config.namespace}`)
    .replace(/^(\s*name:\s*)reef-local\s*$/gm, `$1${config.namespace}`);
}

function kubectlArgs(args, options = {}) {
  const out = [];
  if (config.context && !options.contextOptional) {
    out.push("--context", config.context);
  }
  out.push("-n", config.namespace, ...args);
  return out;
}

function manifest(name) {
  return path.join(manifestsRoot, name);
}

function assertManifests() {
  for (const name of [
    "00-namespace.yaml",
    "10-postgres.yaml",
    "20-nats.yaml",
    "30-matching-engine.yaml",
    "40-platform-api.yaml",
  ]) {
    const file = manifest(name);
    if (!existsSync(file)) {
      throw new Error(`missing local kube manifest: ${file}`);
    }
  }
}

function streamAckWorkerDurables() {
  const partitionGroups = [
    range(0, 15),
    range(16, 31),
    range(32, 47),
    range(48, 63),
  ];
  return partitionGroups
    .flatMap((partitions, workerIndex) =>
      partitions.map((partition) => `reef-kube-stream-worker-w${workerIndex}-${partitionToken(partition)}`),
    )
    .join(",");
}

function range(first, last) {
  return Array.from({ length: last - first + 1 }, (_, index) => first + index);
}

function partitionToken(partition) {
  const partitionCount = 64;
  const width = Math.max(2, String(partitionCount - 1).length);
  return `p${String(partition).padStart(width, "0")}`;
}

function run(cmd, args = [], options = {}) {
  return new Promise((resolve, reject) => {
    const hasInput = options.input !== undefined;
    const child = spawn(cmd, args, {
      cwd: repoRoot,
      stdio: hasInput ? ["pipe", "inherit", "inherit"] : "inherit",
      env: { ...process.env, ...(options.env ?? {}) },
    });
    if (hasInput) {
      child.stdin.end(options.input);
    }
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(`${cmd} ${args.join(" ")} failed with code ${code}`));
    });
  });
}

function runCapture(cmd, args = [], options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      cwd: repoRoot,
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ...(options.env ?? {}) },
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", reject);
    child.on("close", (code) => {
      resolve({ code, stdout, stderr });
    });
  });
}

function stopProcesses(processes) {
  for (const child of processes) {
    if (!child.killed) {
      child.kill("SIGTERM");
    }
  }
}

function waitForSignal(processes) {
  return new Promise((resolve) => {
    const stop = () => {
      stopProcesses(processes);
      resolve();
    };
    process.once("SIGINT", stop);
    process.once("SIGTERM", stop);
    for (const child of processes) {
      child.once("exit", () => {
        if (processes.every((candidate) => candidate.exitCode != null || candidate.signalCode != null)) {
          resolve();
        }
      });
    }
  });
}

function printHelp() {
  console.log(`
usage: ${env("JS_RUNTIME", "bun")} scripts/dev/kube.mjs <command>

commands:
  up             create k3d cluster, build/import images, apply manifests, migrate
  apply          apply manifests to the current local cluster and run migrations
  reset          delete and recreate the base local kube stack
  materializer-up     start the Redpanda/materializer local kube profile
  materializer-smoke  start materializer profile and run durable projection smoke
  materializer-scale  start materializer profile and scale platform-materializer
  stream-ack-up       start Redpanda stream-ack worker/projector profile
  stream-ack-smoke    start stream-ack profile and prove command completion
  autoscale-apply     apply opt-in HPA rules for scalable materializer/read roles
  build-images   build local runtime and engine images
  import-images  import local runtime and engine images into k3d
  migrate        run database migrations through kubectl exec
  port-forward   expose platform-api and matching-engine on localhost
  smoke          port-forward, then run scripts/dev/smoke.mjs
  status         show pods, services, and PVCs
  render-manifest [file]
                 render one manifest with KUBE_NAMESPACE applied; default 00-namespace.yaml
  down           delete the k3d cluster

environment:
  KUBE_LOCAL_CLUSTER=reef-local
  KUBE_NAMESPACE=reef-local
  KUBE_CONTEXT=k3d-reef-local
  KUBE_BUILD_IMAGES=1
  KUBE_IMPORT_IMAGES=1
  KUBE_MATERIALIZER_REPLICAS=4
  REEF_PLATFORM_API_HOST_PORT=8080
  REEF_MATCHING_ENGINE_HOST_PORT=8081
`.trim());
}
