import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../..", import.meta.url));
const failures = [];

assertNoMatch(
  "services/platform-runtime/src/main/kotlin/com/reef/platform/api/ExternalApiBoundary.kt",
  /com\.reef\.platform\.application\.arena/,
  "the Reef external boundary must not import an Arena implementation"
);
assertNoMatch(
  "services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServerBootstrap.kt",
  /ArenaBotVersionRiskCheck/,
  "the Reef bootstrap must discover optional admission policy instead of constructing an Arena risk check"
);
assertNoMatchInTree(
  "services/matching-engine",
  new Set([".go"]),
  /arena/i,
  "the matching engine must not reference Arena"
);
assertNoMatchInTree(
  "services/simulator",
  new Set([".go"]),
  /ARENA_|\/arena\//,
  "the general simulator must not reference Arena configuration or routes"
);
assertNoMatch(
  "compose.base.yml",
  /ARENA_|arena-postgres|reef-arena/i,
  "the Reef base Compose profile must not contain Arena configuration or storage"
);
assertNoMatch(
  "compose.local.yml",
  /ARENA_|arena-postgres|reef-arena/i,
  "the Reef local Compose profile must not contain Arena configuration or storage"
);
assertMatch(
  "compose.arena.yml",
  /arena-postgres/,
  "the Arena overlay must own Arena storage"
);

if (failures.length > 0) {
  console.error(`Reef/Arena boundary check failed:\n${failures.map((failure) => `- ${failure}`).join("\n")}`);
  process.exit(1);
}

console.log("Reef/Arena boundary check passed.");

function assertNoMatch(path, pattern, description) {
  if (pattern.test(read(path))) failures.push(`${path}: ${description}`);
}

function assertMatch(path, pattern, description) {
  if (!pattern.test(read(path))) failures.push(`${path}: ${description}`);
}

function assertNoMatchInTree(root, extensions, pattern, description) {
  for (const path of walk(root)) {
    if (!extensions.has(extension(path))) continue;
    if (pattern.test(read(path))) failures.push(`${path}: ${description}`);
  }
}

function walk(path) {
  const fullPath = join(repoRoot, path);
  return readdirSync(fullPath, { withFileTypes: true }).flatMap((entry) => {
    const child = join(path, entry.name);
    if (entry.isDirectory()) return walk(child);
    return entry.isFile() ? [child] : [];
  });
}

function read(path) {
  return readFileSync(join(repoRoot, path), "utf8");
}

function extension(path) {
  const index = path.lastIndexOf(".");
  return index >= 0 ? path.slice(index) : "";
}
