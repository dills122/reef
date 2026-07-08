import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
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
  waitTimeoutSeconds: Number(env("KUBE_WAIT_TIMEOUT_SECONDS", "300")),
  runtimeHostPort: env("REEF_PLATFORM_API_HOST_PORT", "8080"),
  engineHostPort: env("REEF_MATCHING_ENGINE_HOST_PORT", "8081"),
};

const command = process.argv[2] ?? "help";

switch (command) {
  case "up":
    await up();
    break;
  case "apply":
    await apply();
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
    await importImages();
  }
  await apply();
}

async function apply() {
  assertManifests();
  await kubectl(["apply", "-f", manifest("00-namespace.yaml")], { contextOptional: true });
  await kubectl(["apply", "-f", manifest("10-postgres.yaml")]);
  await kubectl(["apply", "-f", manifest("20-nats.yaml")]);
  await waitStatefulSets(["postgres", "projection-postgres", "boundary-postgres", "arena-postgres", "nats"]);
  await migrate();
  await kubectl(["apply", "-f", manifest("30-matching-engine.yaml")]);
  await kubectl(["apply", "-f", manifest("40-platform-api.yaml")]);
  await kubectl(["set", "image", "deployment/matching-engine", `matching-engine=${config.engineImage}`]);
  await kubectl(["set", "image", "deployment/platform-api", `platform-api=${config.runtimeImage}`]);
  await waitDeployments(["matching-engine", "platform-api"]);
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

async function startPortForwards() {
  await waitDeployments(["matching-engine", "platform-api"]);
  return [
    spawnKubectl([
      "port-forward",
      "svc/platform-api",
      `${config.runtimeHostPort}:8080`,
    ]),
    spawnKubectl([
      "port-forward",
      "svc/matching-engine",
      `${config.engineHostPort}:8081`,
    ]),
  ];
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

function run(cmd, args = [], options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      cwd: repoRoot,
      stdio: "inherit",
      env: { ...process.env, ...(options.env ?? {}) },
    });
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
  build-images   build local runtime and engine images
  import-images  import local runtime and engine images into k3d
  migrate        run database migrations through kubectl exec
  port-forward   expose platform-api and matching-engine on localhost
  smoke          port-forward, then run scripts/dev/smoke.mjs
  status         show pods, services, and PVCs
  down           delete the k3d cluster

environment:
  KUBE_LOCAL_CLUSTER=reef-local
  KUBE_NAMESPACE=reef-local
  KUBE_CONTEXT=k3d-reef-local
  KUBE_BUILD_IMAGES=1
  REEF_PLATFORM_API_HOST_PORT=8080
  REEF_MATCHING_ENGINE_HOST_PORT=8081
`.trim());
}
