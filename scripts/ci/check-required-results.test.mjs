import assert from "node:assert/strict";
import test from "node:test";

import { validateRequiredResults } from "./check-required-results.mjs";

const fullCiJobs = [
  "dependency-alignment",
  "proto-governance",
  "go-matching-engine",
  "go-simulator",
  "kotlin-platform-runtime",
  "kotlin-stock-data",
  "reef-arena-separation",
  "node-dev-tooling",
  "docs-site",
  "bot-sdk",
  "arena-admin",
  "scenario-replay",
  "container-builds",
  "infrastructure-config",
  "go-vulnerability-scan",
  "postgres-schema-placement",
];

function needsWith(result = "success") {
  return Object.fromEntries(fullCiJobs.map((job) => [job, { result, outputs: {} }]));
}

test("accepts a successful full CI run", () => {
  const needs = {
    "change-scope": { result: "success", outputs: { "run-full-ci": "true" } },
    ...needsWith(),
  };

  assert.doesNotThrow(() => validateRequiredResults(needs, { scenarioReplayRequired: true }));
});

test("accepts intentionally skipped full jobs for a bot-only pull request", () => {
  const needs = {
    "change-scope": { result: "success", outputs: { "run-full-ci": "false" } },
    ...needsWith("skipped"),
  };

  assert.doesNotThrow(() => validateRequiredResults(needs, { scenarioReplayRequired: false }));
});

test("accepts an intentionally skipped replay on a human pull request", () => {
  const needs = {
    "change-scope": { result: "success", outputs: { "run-full-ci": "true" } },
    ...needsWith(),
    "scenario-replay": { result: "skipped", outputs: {} },
  };

  assert.doesNotThrow(() => validateRequiredResults(needs, { scenarioReplayRequired: false }));
});

test("rejects any failed full-CI job", () => {
  const needs = {
    "change-scope": { result: "success", outputs: { "run-full-ci": "true" } },
    ...needsWith(),
    "postgres-schema-placement": { result: "failure", outputs: {} },
  };

  assert.throws(
    () => validateRequiredResults(needs, { scenarioReplayRequired: true }),
    /postgres-schema-placement=failure/,
  );
});

test("rejects a replay skip when replay is required", () => {
  const needs = {
    "change-scope": { result: "success", outputs: { "run-full-ci": "true" } },
    ...needsWith(),
    "scenario-replay": { result: "skipped", outputs: {} },
  };

  assert.throws(
    () => validateRequiredResults(needs, { scenarioReplayRequired: true }),
    /scenario-replay=skipped/,
  );
});

test("rejects a failed scope classifier even when full CI is skipped", () => {
  const needs = {
    "change-scope": { result: "failure", outputs: { "run-full-ci": "false" } },
    ...needsWith("skipped"),
  };

  assert.throws(
    () => validateRequiredResults(needs, { scenarioReplayRequired: false }),
    /change-scope=failure/,
  );
});
