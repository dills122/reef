import assert from "node:assert/strict";
import { test } from "node:test";
import { configureProjectionSourceNames, projectionSourceBindingIssues } from "./stream-profile-guard.mjs";

const full = { STREAM_ACK_PROJECTOR_ENABLED: "true", ORDER_LIFECYCLE_PROJECTOR_ENABLED: "true", MARKET_DATA_PROJECTOR_ENABLED: "true" };
test("standalone defaults preserve canonical namespace and bind market source to it", () => {
  for (const canonical of [undefined, "", "custom-canonical"]) {
    const env = { ...full, STREAM_ACK_PROJECTION_NAME: canonical };
    configureProjectionSourceNames(env);
    assert.equal(env.STREAM_ACK_PROJECTION_NAME, canonical || "runtime-normalized-submit");
    assert.equal(env.MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME, env.STREAM_ACK_PROJECTION_NAME);
    assert.deepEqual(projectionSourceBindingIssues(env), []);
  }
});
test("explicit mismatch fails instead of silently renaming canonical or market namespace", () => {
  const env = { ...full, STREAM_ACK_PROJECTION_NAME: "retained-namespace", MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME: "other" };
  configureProjectionSourceNames(env);
  assert.equal(env.STREAM_ACK_PROJECTION_NAME, "retained-namespace");
  assert.equal(env.MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME, "other");
  assert.match(projectionSourceBindingIssues(env).join("\n"), /must match STREAM_ACK_PROJECTION_NAME/);
});
test("guard models real Compose fallback mismatch and effective per-instance maintainers", () => {
  assert.equal(projectionSourceBindingIssues(full).length, 1);
  // Market worker also invokes lifecycle projection when its dedicated worker is off.
  assert.equal(projectionSourceBindingIssues({ ...full, ORDER_LIFECYCLE_PROJECTOR_ENABLED: "false" }).length, 1);
  assert.equal(projectionSourceBindingIssues({ STREAM_ACK_PROJECTOR_ENABLED: "true", ORDER_LIFECYCLE_PROJECTOR_0_ENABLED: "true", MARKET_DATA_PROJECTOR_0_ENABLED: "true" }).length, 1);
  assert.deepEqual(projectionSourceBindingIssues({ ...full, STREAM_ACK_PROJECTION_STAGE: "command-status" }), []);
  assert.deepEqual(projectionSourceBindingIssues({ ...full, STREAM_ACK_PROJECTOR_ENABLED: "false" }), []);
  assert.deepEqual(projectionSourceBindingIssues({ ...full, MARKET_DATA_PROJECTOR_ENABLED: "false" }), []);
  assert.deepEqual(projectionSourceBindingIssues({ ...full, ...Object.fromEntries([0,1,2,3].map(i=>[`MARKET_DATA_PROJECTOR_${i}_ENABLED`,"false"])) }), []);
});
