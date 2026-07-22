import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const documents = [
  "README.md",
  "CONTRIBUTING.md",
  "docs/ONBOARDING.md",
  "docs/DEV_ENV.md",
  "docs/steering/README.md",
  "infra/README.md",
  "apps/arena-admin/README.md",
  "apps/docs-site/README.md",
];

const missing = [];
for (const document of documents) {
  const source = readFileSync(resolve(repoRoot, document), "utf8");
  const links = source.matchAll(/!?\[[^\]]*\]\(([^)]+)\)/g);
  for (const match of links) {
    const rawTarget = match[1].trim().replace(/^<|>$/g, "");
    if (/^(?:https?:|mailto:|#)/.test(rawTarget)) continue;
    const target = decodeURIComponent(rawTarget.split("#", 1)[0]);
    if (!target) continue;
    const resolved = resolve(repoRoot, dirname(document), target);
    if (!existsSync(resolved)) missing.push(`${document} -> ${rawTarget}`);
  }
}

assert.deepEqual(missing, [], `missing onboarding links:\n${missing.join("\n")}`);
console.log(`onboarding links ok (${documents.length} documents)`);
