import { existsSync, realpathSync } from "node:fs";
import { delimiter, join } from "node:path";

export const hostedSesLockdownOptions = Object.freeze({
  errorTaming: "safe",
  stackFiltering: "concise",
  consoleTaming: "safe",
});

export function hostedWorkerProcessEnv(extra = {}) {
  return {
    PATH: process.env.PATH ?? "",
    HOME: "/tmp",
    TMPDIR: "/tmp",
    BUN_INSTALL_CACHE_DIR: "/tmp/bun-cache",
    NODE_ENV: "test",
    ...(process.env.NODE_PATH === undefined ? {} : { NODE_PATH: process.env.NODE_PATH }),
    ...(process.env.REEF_NODE_MODULES_DIR === undefined ? {} : { REEF_NODE_MODULES_DIR: process.env.REEF_NODE_MODULES_DIR }),
    ...extra,
  };
}

export function hostedBotContainerArgs({
  repoRoot,
  command,
  image = process.env.BOT_SDK_SES_CONTAINER_IMAGE || "oven/bun:1.1.38",
  cpus = process.env.BOT_SDK_CONTAINER_CPUS || "1",
  memory = process.env.BOT_SDK_CONTAINER_MEMORY || "256m",
  pidsLimit = process.env.BOT_SDK_CONTAINER_PIDS_LIMIT || "64",
  network = process.env.BOT_SDK_CONTAINER_NETWORK || "none",
} = {}) {
  if (!repoRoot) {
    throw new Error("repoRoot is required");
  }
  if (!Array.isArray(command) || command.length === 0) {
    throw new Error("container command is required");
  }

  const normalizedRepoRoot = repoRoot.replace(/\/$/, "");
  const nodeModulesMount = resolvedNodeModulesMount(normalizedRepoRoot);
  return [
    "run",
    "--rm",
    "--interactive",
    `--network=${network}`,
    `--cpus=${cpus}`,
    `--memory=${memory}`,
    `--pids-limit=${pidsLimit}`,
    "--read-only",
    "--tmpfs",
    "/tmp:rw,nosuid,nodev,noexec,size=64m",
    "-e",
    "HOME=/tmp",
    "-e",
    "TMPDIR=/tmp",
    "-e",
    "BUN_INSTALL_CACHE_DIR=/tmp/bun-cache",
    "-e",
    "NODE_ENV=test",
    ...(nodeModulesMount === undefined ? [] : ["-e", "NODE_PATH=/node_modules"]),
    "-v",
    `${normalizedRepoRoot}:/workspace:ro`,
    ...(nodeModulesMount === undefined ? [] : ["-v", `${nodeModulesMount}:/node_modules:ro`]),
    "-w",
    "/workspace",
    image,
    ...command,
  ];
}

function resolvedNodeModulesMount(repoRoot) {
  for (const nodeModules of candidateNodeModulesDirs(repoRoot)) {
    if (existsSync(nodeModules)) {
      return realpathSync(nodeModules);
    }
  }
  return undefined;
}

function candidateNodeModulesDirs(repoRoot) {
  return [
    process.env.REEF_NODE_MODULES_DIR,
    ...(process.env.NODE_PATH?.split(delimiter) ?? []),
    join(repoRoot, "node_modules"),
  ].filter((candidate) => candidate !== undefined && candidate.length > 0);
}
