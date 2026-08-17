import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import { codexSetupArgs, isPathWithin, resolveAiCentralLocation } from "./dev-links.mjs";

test("AI Central defaults to the user profile checkout", () => {
  assert.deepEqual(resolveAiCentralLocation(undefined, "/Users/example"), {
    aiCentralRoot: path.resolve("/Users/example/.ai-central"),
    templatesRoot: path.resolve("/Users/example/.ai-central/templates"),
  });
});

test("AI_CENTRAL_HOME accepts either the checkout or templates directory", () => {
  assert.deepEqual(resolveAiCentralLocation("/opt/ai-central"), {
    aiCentralRoot: path.resolve("/opt/ai-central"),
    templatesRoot: path.resolve("/opt/ai-central/templates"),
  });
  assert.deepEqual(resolveAiCentralLocation("/opt/ai-central/templates"), {
    aiCentralRoot: path.resolve("/opt/ai-central"),
    templatesRoot: path.resolve("/opt/ai-central/templates"),
  });
});

test("Codex refresh synchronizes Reef's curated bundle selection", () => {
  const args = codexSetupArgs(true);

  assert.deepEqual(args.slice(1), [
    "--yes",
    "--mode",
    "link",
    "--bundles",
    "core,node,jvm,frontend,infra,workflow,planning,orchestration,documentation,brevity",
    "--sync",
    "--dry-run",
  ]);
});

test("managed-link containment does not accept siblings or the root itself", () => {
  assert.equal(isPathWithin("/opt/ai-central/skills", "/opt/ai-central/skills/core"), true);
  assert.equal(isPathWithin("/opt/ai-central/skills", "/opt/ai-central/skills-extra/core"), false);
  assert.equal(isPathWithin("/opt/ai-central/skills", "/opt/ai-central/skills"), false);
});
