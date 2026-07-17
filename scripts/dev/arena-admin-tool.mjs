import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const appRoot = join(repoRoot, "apps", "arena-admin");
const nodeVersion = process.env.REEF_ARENA_ADMIN_NODE_VERSION || "22.22.1";

const commands = new Map([
  ["sync", ["node_modules/@sveltejs/kit/svelte-kit.js", "sync"]],
  ["check", ["node_modules/svelte-check/bin/svelte-check", "--tsconfig", "./tsconfig.json"]],
  ["dev", ["node_modules/vite/bin/vite.js", "dev"]],
  ["build", ["node_modules/vite/bin/vite.js", "build"]],
  ["preview", ["node_modules/vite/bin/vite.js", "preview"]],
]);

const command = process.argv[2];
const passthroughArgs = process.argv.slice(3);

if (command === "sync-optional") {
  const result = runAppNodeCommand(commands.get("sync"));
  if (result.status !== 0) {
    console.log("");
  }
  process.exit(0);
}

if (command === "check") {
  process.exit(runSequence([commands.get("sync"), [...commands.get("check"), ...passthroughArgs]]));
}

if (!commands.has(command)) {
  console.error("usage: bun scripts/dev/arena-admin-tool.mjs <sync|sync-optional|check|dev|build|preview> [args...]");
  process.exit(2);
}

process.exit(runAppNodeCommand([...commands.get(command), ...passthroughArgs]).status ?? 1);

function runSequence(sequence) {
  for (const args of sequence) {
    const result = runAppNodeCommand(args);
    if (result.status !== 0) {
      return result.status ?? 1;
    }
  }
  return 0;
}

function runAppNodeCommand(args) {
  assertAppDependencies(args[0]);

  if (hasCommand("fnm")) {
    return spawnSync("fnm", ["exec", `--using=${nodeVersion}`, "--", "node", ...args], {
      cwd: appRoot,
      stdio: "inherit",
      env: process.env,
    });
  }

  assertCurrentNodeSupportsTooling();
  return spawnSync("node", args, {
    cwd: appRoot,
    stdio: "inherit",
    env: process.env,
  });
}

function assertAppDependencies(relativeBinPath) {
  if (existsSync(join(appRoot, relativeBinPath))) {
    return;
  }
  console.error("missing arena-admin dependencies. Run: cd apps/arena-admin && bun install --frozen-lockfile");
  process.exit(1);
}

function assertCurrentNodeSupportsTooling() {
  const result = spawnSync("node", ["-e", "process.exit(typeof require('node:util').styleText === 'function' ? 0 : 1)"], {
    cwd: appRoot,
    stdio: "ignore",
    env: process.env,
  });
  if (result.status === 0) {
    return;
  }
  console.error(`arena-admin requires Node ${nodeVersion} or another Node with node:util.styleText. Install fnm or fix PATH.`);
  process.exit(1);
}

function hasCommand(commandName) {
  const result = spawnSync(commandName, ["--version"], {
    cwd: repoRoot,
    stdio: "ignore",
    env: process.env,
  });
  return result.status === 0;
}
