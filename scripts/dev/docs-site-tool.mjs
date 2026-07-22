import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const appRoot = join(repoRoot, "apps/docs-site");
const nodeVersion = readFileSync(join(repoRoot, ".node-version"), "utf8").trim();
const command = process.argv[2];
const passthroughArgs = process.argv.slice(3);

if (command === "install") {
  process.exit(runWithPinnedNode("npm", ["ci", "--include=optional"]).status ?? 1);
}

if (!["dev", "build", "preview", "check"].includes(command)) {
  console.error("usage: bun scripts/dev/docs-site-tool.mjs <install|dev|build|preview|check> [args...]");
  process.exit(2);
}

const astro = "node_modules/astro/bin/astro.mjs";
if (!existsSync(join(appRoot, astro))) {
  console.error("missing docs-site dependencies. Run: make dev-bootstrap");
  process.exit(1);
}

process.exit(runWithPinnedNode("node", [astro, command, ...passthroughArgs]).status ?? 1);

function runWithPinnedNode(executable, args) {
  if (hasCommand("fnm")) {
    return spawnSync("fnm", ["exec", `--using=${nodeVersion}`, "--", executable, ...args], {
      cwd: appRoot,
      stdio: "inherit",
      env: process.env,
    });
  }

  assertCurrentNodeSupportsTooling();
  return spawnSync(executable, args, {
    cwd: appRoot,
    stdio: "inherit",
    env: process.env,
  });
}

function assertCurrentNodeSupportsTooling() {
  const result = spawnSync("node", ["-p", "process.versions.node"], {
    cwd: appRoot,
    encoding: "utf8",
    env: process.env,
  });
  const [major = 0, minor = 0] = (result.stdout ?? "").trim().split(".").map(Number);
  if (result.status === 0 && major === 22 && minor >= 12) return;
  console.error(`docs-site requires Node ${nodeVersion} (or compatible Node 22.12+). Install fnm or fix PATH.`);
  process.exit(1);
}

function hasCommand(commandName) {
  return spawnSync(commandName, ["--version"], {
    cwd: repoRoot,
    stdio: "ignore",
    env: process.env,
  }).status === 0;
}
