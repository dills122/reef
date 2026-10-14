import assert from "node:assert/strict";
import test from "node:test";
import { classifyScriptSurface } from "./script-surface.mjs";

function classify(files) {
  return Object.fromEntries(classifyScriptSurface(Object.keys(files).filter((p) => p.startsWith("scripts/")), new Map(Object.entries(files))).map((row) => [row.path, row.category]));
}

test("control commands root script imports, resolving paths against importer", () => {
  const result = classify({
    "Makefile": "run:\n\tnode scripts/dev/run.mjs",
    "scripts/dev/run.mjs": 'import "./lib/shared.mjs";',
    "scripts/dev/lib/shared.mjs": "export const value = 1;",
    "scripts/other/shared.mjs": "export const value = 2;",
  });
  assert.equal(result["scripts/dev/run.mjs"], "runnable");
  assert.equal(result["scripts/dev/lib/shared.mjs"], "imported");
  assert.equal(result["scripts/other/shared.mjs"], "unreferenced");
});

test("workflow ./scripts paths are repository relative", () => {
  assert.equal(classify({ ".github/workflows/ci.yml": "run: ./scripts/check.sh", "scripts/check.sh": "true" })["scripts/check.sh"], "runnable");
});

test("tests and historical evidence do not establish operational use", () => {
  const result = classify({
    "package.json": JSON.stringify({ scripts: { test: "node --test scripts/probe.test.mjs" } }),
    "scripts/probe.test.mjs": 'import "./probe.mjs";',
    "scripts/probe.mjs": "export const value = 1;",
    "scripts/old.mjs": "",
    "docs/evidence/run.md": "node scripts/old.mjs",
  });
  assert.equal(result["scripts/probe.test.mjs"], "test");
  assert.equal(result["scripts/probe.mjs"], "test-only");
  assert.equal(result["scripts/old.mjs"], "evidence-only");
});

test("operational docs require invocation, not a catalog mention", () => {
  const result = classify({
    "docs/OPERATIONS.md": "Run `bun scripts/run.mjs`.\nSee [catalog](../scripts/stale.mjs).",
    "scripts/run.mjs": "", "scripts/stale.mjs": "",
  });
  assert.equal(result["scripts/run.mjs"], "operational-doc");
  assert.equal(result["scripts/stale.mjs"], "unreferenced");
});

test("orphan import cycles, comments and package metadata cannot establish use", () => {
  const result = classify({
    "Makefile": "# node scripts/a.mjs",
    "package.json": JSON.stringify({ description: "scripts/a.mjs" }),
    "scripts/a.mjs": 'import "./b.mjs";',
    "scripts/b.mjs": 'import "./a.mjs";',
    "scripts/c.mjs": '// import "./unused.mjs";',
    "scripts/unused.mjs": "",
  });
  assert.equal(result["scripts/a.mjs"], "unreachable");
  assert.equal(result["scripts/b.mjs"], "unreachable");
  assert.equal(result["scripts/unused.mjs"], "unreferenced");
});

test("library tests are checked, and test imports do not wire another test", () => {
  const result = classify({
    "Makefile": "test:\n\tnode --test scripts/a.test.mjs",
    "scripts/a.test.mjs": 'import "./lib/b.test.mjs";',
    "scripts/lib/b.test.mjs": "",
  });
  assert.equal(result["scripts/lib/b.test.mjs"], "unwired-test");
});
