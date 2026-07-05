import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const { validateVenuePreflightV1 } = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/venue-preflight.ts")).href);

const report = validateVenuePreflightV1(fixture);
assert.equal(report.status, "ready");
assert.equal(report.issues.length, 0);
assert.ok(report.requirements.some((requirement) => requirement.kind === "instrument" && requirement.id === "AAPL"));
assert.ok(report.requirements.some((requirement) => requirement.kind === "actor_role_binding"));

const missing = validateVenuePreflightV1({ ...fixture, actorId: "", ticks: [] });
assert.equal(missing.status, "not_ready");
assert.ok(missing.issues.some((issue) => issue.code === "missing_fixture_field"));
assert.ok(missing.issues.some((issue) => issue.code === "missing_ticks"));

console.log("bot SDK venue preflight checks passed");
