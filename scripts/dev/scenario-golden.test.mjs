import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { compareGoldenJsonFiles } from "./lib/scenario-golden.mjs";

const dir = mkdtempSync(join(tmpdir(), "reef-scenario-golden-test-"));
const expectedPath = join(dir, "expected.json");
const actualPath = join(dir, "actual.json");

writeFileSync(expectedPath, JSON.stringify({
  pass: true,
  requests: [{ sequence: 1, payload: { commandId: "cmd-1", quantityUnits: "100" } }],
}, null, 2));
writeFileSync(actualPath, JSON.stringify({
  pass: true,
  requests: [{ sequence: 1, payload: { commandId: "cmd-1", quantityUnits: "100" } }],
}, null, 2));

const clean = compareGoldenJsonFiles(expectedPath, actualPath);
assert.equal(clean.pass, true);
assert.deepEqual(clean.failures, []);

writeFileSync(actualPath, JSON.stringify({
  pass: true,
  requests: [{ sequence: 1, payload: { commandId: "cmd-1", quantityUnits: "99" } }],
}, null, 2));

const drift = compareGoldenJsonFiles(expectedPath, actualPath);
assert.equal(drift.pass, false);
assert.ok(drift.failures.some((failure) => failure.includes("requests[0].payload.quantityUnits changed")));
