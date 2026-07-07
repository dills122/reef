import { spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import { resolve } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname.replace(/\/$/, "");
const image = process.env.ARENA_RUNNER_CONTAINER_IMAGE || "oven/bun:1.1.38";
const artifactDir = process.env.ARENA_RUNNER_CONTAINER_ARTIFACT_DIR || "/tmp/reef-arena-runner-container";
const cpuLimit = process.env.ARENA_RUNNER_CONTAINER_CPUS || "1";
const memoryLimit = process.env.ARENA_RUNNER_CONTAINER_MEMORY || "256m";
const pidsLimit = process.env.ARENA_RUNNER_CONTAINER_PIDS || "128";
const tmpSize = process.env.ARENA_RUNNER_CONTAINER_TMP_SIZE || "128m";
const command = process.argv.slice(2);
const benchCommand = command.length === 0
  ? [
      "bun",
      "scripts/dev/arena-runner-realworld-bench.mjs",
      "--bots=simple,lifecycle,refreshing,multi-symbol,technical",
      "--iterations=25",
      "--concurrency=4",
      "--compartment=ses",
      "--out=/artifacts/reef-arena-runner-container-realworld-ses.json",
    ]
  : command;

mkdirSync(artifactDir, { recursive: true });

const args = [
  "run",
  "--rm",
  "--network=none",
  `--cpus=${cpuLimit}`,
  `--memory=${memoryLimit}`,
  `--pids-limit=${pidsLimit}`,
  "--read-only",
  "--tmpfs",
  `/tmp:rw,nosuid,nodev,noexec,size=${tmpSize}`,
  "-e",
  "HOME=/tmp",
  "-e",
  "BUN_INSTALL_CACHE_DIR=/tmp/bun-cache",
  "-v",
  `${repoRoot}:/workspace:ro`,
  "-v",
  `${artifactDir}:/artifacts:rw`,
  "-w",
  "/workspace",
  image,
  ...benchCommand,
];

console.log(`running arena runner container bench with ${image}`);
console.log(`artifact dir: ${resolve(artifactDir)}`);
console.log(`limits: cpus=${cpuLimit} memory=${memoryLimit} pids=${pidsLimit} tmp=${tmpSize}`);
console.log(`command: ${benchCommand.join(" ")}`);

const child = spawn("docker", args, { stdio: "inherit" });
child.on("error", (error) => {
  console.error(error.message);
  process.exit(1);
});
child.on("close", (code) => {
  process.exit(code ?? 1);
});
