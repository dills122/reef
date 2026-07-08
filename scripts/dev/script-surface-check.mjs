import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../..", import.meta.url));
const scriptExts = new Set([".js", ".mjs", ".sh", ".ts"]);
const nodeCheckExts = new Set([".js", ".mjs"]);
const shellCheckExts = new Set([".sh"]);
const skipDirs = new Set([
  ".git",
  ".gradle",
  ".idea",
  ".codex",
  ".claude",
  "build",
  "coverage",
  "dist",
  "node_modules",
  "reports",
]);

const scriptFiles = walk("scripts")
  .filter((path) => scriptExts.has(extension(path)))
  .sort();

const failures = [];
const syntaxChecked = [];
const shellChecked = [];
const syntaxSkipped = [];

for (const path of scriptFiles) {
  if (nodeCheckExts.has(extension(path))) {
    runCheck(process.execPath, ["--check", path], `${path} JS syntax`);
    syntaxChecked.push(path);
    continue;
  }
  if (shellCheckExts.has(extension(path))) {
    runCheck("bash", ["-n", path], `${path} shell syntax`);
    shellChecked.push(path);
    continue;
  }
  syntaxSkipped.push(path);
}

const repoFiles = walk(".")
  .filter((path) => path !== ".")
  .filter((path) => isReadableTextCandidate(path))
  .sort();
const repoText = new Map();
for (const path of repoFiles) {
  try {
    repoText.set(path, readFileSync(path, "utf8"));
  } catch {
    // Permission-restricted local agent/plugin files are intentionally ignored.
  }
}

const controlFiles = [
  "Makefile",
  "package.json",
  ...walk(".github").filter((path) => /\.(ya?ml)$/.test(path)),
].filter((path) => existsSync(path));
const controlText = controlFiles.map((path) => repoText.get(path) ?? readFileSync(path, "utf8")).join("\n");

const unreferencedEntrypoints = [];
const unwiredTests = [];

for (const path of scriptFiles) {
  if (path.includes("/lib/")) continue;
  if (path.includes("/db/migrations/")) continue;

  if (path.endsWith(".test.mjs")) {
    if (!controlText.includes(path)) {
      unwiredTests.push(path);
    }
    continue;
  }

  if (!hasReference(path)) {
    unreferencedEntrypoints.push(path);
  }
}

if (unreferencedEntrypoints.length > 0) {
  failures.push([
    "script entrypoints without a Makefile/package/CI/docs/import reference:",
    ...unreferencedEntrypoints.map((path) => `  - ${path}`),
  ].join("\n"));
}

if (unwiredTests.length > 0) {
  failures.push([
    "script tests not wired into Makefile, package.json, or CI:",
    ...unwiredTests.map((path) => `  - ${path}`),
  ].join("\n"));
}

if (failures.length > 0) {
  console.error(failures.join("\n\n"));
  process.exit(1);
}

console.log("script surface check passed");
console.log(`  scripts=${scriptFiles.length}`);
console.log(`  nodeSyntaxChecked=${syntaxChecked.length}`);
console.log(`  shellSyntaxChecked=${shellChecked.length}`);
if (syntaxSkipped.length > 0) {
  console.log(`  syntaxSkipped=${syntaxSkipped.join(", ")}`);
}
console.log(`  controlFiles=${controlFiles.join(", ")}`);

function hasReference(scriptPath) {
  const localReference = `./${scriptPath.split("/").pop()}`;
  for (const [path, text] of repoText) {
    if (path === scriptPath) continue;
    if (text.includes(scriptPath) || text.includes(localReference)) return true;
  }
  return false;
}

function runCheck(cmd, args, label) {
  const result = spawnSync(cmd, args, {
    cwd: repoRoot,
    encoding: "utf8",
  });
  if (result.status === 0) return;
  failures.push([
    `${label} failed with status ${result.status ?? "signal"}`,
    result.stdout.trim(),
    result.stderr.trim(),
  ].filter(Boolean).join("\n"));
}

function walk(start) {
  if (!existsSync(start)) return [];
  const out = [];
  visit(start);
  return out.map(toPosix);

  function visit(path) {
    const stat = statSync(path);
    if (stat.isDirectory()) {
      const name = path.split(sep).pop();
      if (name && skipDirs.has(name)) return;
      for (const entry of readdirSync(path).sort()) {
        visit(`${path}${sep}${entry}`);
      }
      return;
    }
    if (stat.isFile()) out.push(path);
  }
}

function isReadableTextCandidate(path) {
  return /(^Makefile$|\.md$|\.json$|\.ya?ml$|\.toml$|\.mjs$|\.js$|\.ts$|\.sh$|\.kt$|\.go$)$/.test(path);
}

function extension(path) {
  const dot = path.lastIndexOf(".");
  return dot === -1 ? "" : path.slice(dot);
}

function toPosix(path) {
  return relative(repoRoot, path).split(sep).join("/");
}
