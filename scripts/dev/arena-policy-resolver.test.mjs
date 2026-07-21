import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  canonicalHash,
  canonicalJson,
  isCanonicalSha256,
  resolveActorProfile,
  resolveActorProfileCatalog,
  resolveEconomicPolicy,
  resolvePolicyComposition,
} from "./lib/arena-policy-resolver.mjs";

const repoRoot = new URL("../../", import.meta.url).pathname;
const readJson = (path) => JSON.parse(readFileSync(new URL(path, `file://${repoRoot}`).pathname, "utf8"));
const actorFixture = readJson("packages/scenario-definitions/arena/actor-profiles.v1.json");
const modeFixture = readJson("packages/scenario-definitions/arena/equity-sprint.v1.json");
const economicPaths = [
  "packages/scenario-definitions/arena/economics/preview-zero-fee-v1.json",
  "packages/scenario-definitions/arena/economics/preview-balanced-fee-v1.json",
  "packages/scenario-definitions/arena/economics/preview-liquidity-subsidy-v1.json",
];

assert.equal(canonicalJson({ z: [2, { b: true, a: "x" }], a: 1 }), '{"a":1,"z":[2,{"a":"x","b":true}]}');
assert.equal(canonicalHash({ b: 2, a: 1 }), canonicalHash({ a: 1, b: 2 }));
assert.throws(() => canonicalHash({ invalid: Number.NaN }), /non-finite/);

const actorCatalog = resolveActorProfileCatalog(actorFixture);
assert.equal(actorCatalog.catalogId, "arena-actor-profiles");
assert.equal(actorCatalog.profilesById.size, 6);
assert.equal(isCanonicalSha256(actorCatalog.contentHash), true);

const actor = resolveActorProfile(
  {
    botId: "competitor-test",
    actorClass: "competitor",
    actorProfileParams: { aggression: 0.65 },
  },
  actorCatalog,
  "competitor-standard",
);
assert.equal(actor.params.aggression, 0.65);
assert.equal(actor.profileVersion, "v1");
assert.equal(isCanonicalSha256(actor.profileHash), true);
assert.throws(
  () => resolveActorProfile(
    { botId: "competitor-test", actorClass: "competitor", actorProfileParams: { secretAdvantage: true } },
    actorCatalog,
    "competitor-standard",
  ),
  /unknown field secretAdvantage/,
);
assert.throws(
  () => resolveActorProfileCatalog({ ...actorFixture, operatorOverride: true }),
  /unknown field operatorOverride/,
);

const economicPolicies = economicPaths.map((path) => resolveEconomicPolicy(readJson(path)));
assert.deepEqual(economicPolicies.map((policy) => policy.version), [
  "preview-zero-fee-v1",
  "preview-balanced-fee-v1",
  "preview-liquidity-subsidy-v1",
]);
assert.equal(new Set(economicPolicies.map((policy) => policy.contentHash)).size, 3);
assert.equal(economicPolicies.every((policy) => isCanonicalSha256(policy.contentHash)), true);
assert.throws(
  () => resolveEconomicPolicy({ ...readJson(economicPaths[0]), fees: { ...readJson(economicPaths[0]).fees, hiddenFeeBps: "1" } }),
  /unknown field hiddenFeeBps/,
);

const composition = resolvePolicyComposition(modeFixture, actorCatalog, economicPolicies[0]);
assert.equal(composition.economicPolicy.version, "preview-zero-fee-v1");
assert.equal(composition.actorProfileCatalog.contentHash, actorCatalog.contentHash);
assert.equal(isCanonicalSha256(composition.compositionHash), true);
assert.throws(
  () => resolvePolicyComposition({ ...modeFixture, economicPolicyVersion: "caller-selected-v9" }, actorCatalog, economicPolicies[0]),
  /economicPolicyVersion must be preview-zero-fee-v1/,
);

console.log("arena policy resolver checks passed");
