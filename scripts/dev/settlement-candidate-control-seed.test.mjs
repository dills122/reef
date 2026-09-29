import assert from "node:assert/strict";
import test from "node:test";
import { buildControlSeedPlan, submitControlSeedPlan } from "./settlement-candidate-control-seed.mjs";

const runId = "venue-event-materializer-mixed-lifecycle-stress";
const eventStream = "REEF_MATERIALIZER_STRESS_VENUE_EVENTS";
const sourceGeneration = "123e4567-e89b-12d3-a456-426614174000";
const policy = { profileId: "instant-post-trade-v1", policyVersion: 1,
  mode: "instant-post-trade", settlementCycle: "T+0",
  nettingMode: "gross-or-microbatch", ledgerPostingMode: "near-instant-finality",
  selectionSource: "environment:POST_TRADE_PROFILE" };
const actors = ["materializer-mm-01", "materializer-inst-01", "materializer-inst-02",
  "materializer-retail-01", "materializer-retail-02"];
const fixture = `session:\n  name: venue-event-materializer-mixed-lifecycle-stress\n` +
  `  scenarioRunId: ${runId}\nmarket:\n  equities:\n` +
  Array.from({ length: 64 }, (_, index) => `    - symbol: EQ${index}\n` +
    `      instrumentId: EQ${index}\n`).join("") +
  `actors:\n` + actors.map((actor) => `  - actorId: ${actor}\n`).join("");
const options = { runId, eventStream, sourceGeneration, partitionCount: 16,
  openingAmount: "1000000000000000000000000", policy };

test("generated stress fixture yields explicit policy and 325 ordered openings", () => {
  const plan = buildControlSeedPlan(fixture, options);
  assert.equal(plan.expectedControlSequence, 326);
  assert.equal(plan.actors, 5);
  assert.equal(plan.instruments, 64);
  assert.equal(plan.controls[0].kind, "POLICY");
  assert.equal(plan.controls[0].sourceFrontiers.length, 16);
  assert.equal(plan.controls[0].sourceFrontiers[15].sequence, (15n << 48n).toString());
  assert.equal(plan.controls[1].participantId, "materializer-mm-01-participant");
  assert.equal(plan.controls[1].accountId, "materializer-mm-01-account");
  assert.equal(plan.controls[1].assetType, "CASH");
  assert.equal(plan.controls.at(-1).assetType, "SECURITY");
  assert.equal(new Set(plan.controls.map((control) => control.controlId)).size, 326);
  assert.deepEqual(buildControlSeedPlan(fixture, options), plan);
});

test("missing financial facts and changed fixture fail before accepting controls", () => {
  assert.throws(() => buildControlSeedPlan(fixture, { ...options, openingAmount: undefined }),
    /openingAmount/);
  assert.throws(() => buildControlSeedPlan(fixture, { ...options, policy: { ...policy,
    nettingMode: undefined } }), /policy.nettingMode/);
  assert.throws(() => buildControlSeedPlan(fixture, { ...options, policy: { ...policy,
    settlementCycle: "T+1" } }), /D-050/);
  assert.throws(() => buildControlSeedPlan(fixture, { ...options, runId: "other-run" }),
    /scenarioRunId differs/);
  assert.throws(() => buildControlSeedPlan(fixture + "  - actorId: materializer-mm-01\n",
    options), /distinct nonempty actors/);
});

test("submission verifies idempotent control identity and exact sequence", async () => {
  const plan = buildControlSeedPlan(fixture, options);
  let next = 0;
  const fetchImpl = async (_url, request) => {
    const body = JSON.parse(request.body);
    assert.equal(body.controlId, plan.controls[next].controlId);
    assert.equal(request.headers.Authorization, "Bearer token");
    assert.equal(request.redirect, "error");
    next++;
    return { ok: true, status: 200, json: async () => ({ status: "accepted",
      controlId: body.controlId, controlSequence: next, duplicate: next <= 3 }) };
  };
  const result = await submitControlSeedPlan(plan, { runtimeUrl: "http://127.0.0.1:8080",
    adminToken: "token", fetchImpl });
  assert.equal(next, 326);
  assert.equal(result.duplicates, 3);
  assert.equal(result.expectedControlSequence, 326);
  await assert.rejects(submitControlSeedPlan({ ...plan, controls: plan.controls.slice(0, 1) }, {
    runtimeUrl: "http://127.0.0.1:8080", adminToken: "token",
    fetchImpl: async () => ({ ok: true, status: 200, json: async () => ({
      status: "accepted", controlId: plan.controls[0].controlId,
      controlSequence: 2, duplicate: true }) }),
  }), /out of order/);
  for (const runtimeUrl of ["https://localhost:8080", "http://example.com:8080",
    "http://user:pass@localhost:8080", "http://localhost:8080/proxy"]) {
    await assert.rejects(submitControlSeedPlan(plan, { runtimeUrl, adminToken: "token",
      fetchImpl: async () => { throw new Error("must not send token"); } }), /loopback HTTP origin/);
  }
});
