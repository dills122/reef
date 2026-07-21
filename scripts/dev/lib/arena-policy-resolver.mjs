import { createHash } from "node:crypto";

const SHA256_PATTERN = /^sha256:[a-f0-9]{64}$/;
const ACTOR_CLASSES = new Set(["benchmark", "competitor", "house_market_maker", "npc_flow"]);
const SCORE_EFFECTS = new Set(["diagnostic-only", "difficulty-bucket", "eligible-for-score"]);
const LEDGERS = new Set(["competition", "house"]);

export function canonicalJson(value) {
  assertJsonValue(value, "value");
  return stableStringify(value);
}

export function canonicalHash(value) {
  return `sha256:${createHash("sha256").update(canonicalJson(value)).digest("hex")}`;
}

export function resolveActorProfileCatalog(catalog) {
  object(catalog, "actor profile catalog");
  exactKeys(catalog, ["schemaVersion", "catalogId", "version", "profiles"], "actor profile catalog");
  equal(catalog.schemaVersion, "reef.arena.actorProfiles.v1", "actor profile catalog schemaVersion");
  token(catalog.catalogId, "actor profile catalog catalogId");
  token(catalog.version, "actor profile catalog version");
  array(catalog.profiles, "actor profile catalog profiles");
  nonEmpty(catalog.profiles, "actor profile catalog profiles");

  const profileIds = new Set();
  const profiles = catalog.profiles.map((profile, index) => {
    const path = `actor profile catalog profiles[${index}]`;
    object(profile, path);
    exactKeys(profile, [
      "profileId",
      "version",
      "actorClass",
      "description",
      "difficultyBucket",
      "scoreEffect",
      "allowedParamKeys",
      "params",
    ], path);
    token(profile.profileId, `${path}.profileId`);
    if (profileIds.has(profile.profileId)) throw new Error(`duplicate actor profile ${profile.profileId}`);
    profileIds.add(profile.profileId);
    token(profile.version, `${path}.version`);
    member(profile.actorClass, ACTOR_CLASSES, `${path}.actorClass`);
    token(profile.description, `${path}.description`);
    token(profile.difficultyBucket, `${path}.difficultyBucket`);
    member(profile.scoreEffect, SCORE_EFFECTS, `${path}.scoreEffect`);
    array(profile.allowedParamKeys, `${path}.allowedParamKeys`);
    const allowed = uniqueTokens(profile.allowedParamKeys, `${path}.allowedParamKeys`);
    object(profile.params, `${path}.params`);
    exactKeys(profile.params, [...allowed], `${path}.params`, { allowMissing: true });
    for (const [key, value] of Object.entries(profile.params)) {
      scalar(value, `${path}.params.${key}`);
    }
    return structuredClone(profile);
  });

  const resolved = {
    schemaVersion: catalog.schemaVersion,
    catalogId: catalog.catalogId,
    version: catalog.version,
    profiles,
  };
  return {
    ...resolved,
    contentHash: canonicalHash(resolved),
    profilesById: new Map(profiles.map((profile) => [profile.profileId, profile])),
  };
}

export function resolveActorProfile(bot, catalog, defaultProfileId) {
  object(bot, "bot actor profile selection");
  const profileId = bot.actorProfileRef ?? defaultProfileId;
  token(profileId, `bot ${bot.botId ?? "<unknown>"} actorProfileRef`);
  const profile = catalog.profilesById.get(profileId);
  if (profile === undefined) {
    throw new Error(`bot ${bot.botId ?? "<unknown>"} references unknown actor profile ${profileId}`);
  }
  const actorClass = bot.actorClass;
  if (profile.actorClass !== actorClass) {
    throw new Error(`bot ${bot.botId ?? "<unknown>"} maps to ${actorClass} but actor profile ${profileId} is ${profile.actorClass}`);
  }
  const overrides = bot.actorProfileParams ?? {};
  object(overrides, `bot ${bot.botId ?? "<unknown>"} actorProfileParams`);
  exactKeys(overrides, profile.allowedParamKeys, `bot ${bot.botId ?? "<unknown>"} actorProfileParams`, { allowMissing: true });
  for (const [key, value] of Object.entries(overrides)) scalar(value, `bot ${bot.botId ?? "<unknown>"} actorProfileParams.${key}`);

  const resolved = {
    profileId,
    profileVersion: profile.version,
    actorClass,
    difficultyBucket: profile.difficultyBucket,
    scoreEffect: profile.scoreEffect,
    params: { ...profile.params, ...overrides },
  };
  return { ...resolved, profileHash: canonicalHash(resolved) };
}

export function resolveEconomicPolicy(policy) {
  object(policy, "economic policy");
  exactKeys(policy, [
    "schemaVersion",
    "policyId",
    "version",
    "currency",
    "competitionLedger",
    "houseLedger",
    "fees",
    "rebates",
    "sources",
    "sinks",
    "reconciliation",
  ], "economic policy");
  equal(policy.schemaVersion, "reef.arena.economicPolicy.v1", "economic policy schemaVersion");
  token(policy.policyId, "economic policy policyId");
  token(policy.version, "economic policy version");
  currency(policy.currency, "economic policy currency");

  exactDecimalObject(policy.competitionLedger, ["startingCashPerCompetitor", "allowNegativeCash"], "economic policy competitionLedger", ["allowNegativeCash"]);
  exactDecimalObject(policy.houseLedger, ["marketMakerStartingCash", "npcStartingCash", "subsidyBudget"], "economic policy houseLedger");
  exactDecimalObject(policy.fees, ["makerBps", "takerBps", "cancelFee", "borrowBps", "liquidationPenaltyBps"], "economic policy fees");
  exactKeys(policy.rebates, ["makerBps", "fundingSource"], "economic policy rebates");
  decimal(policy.rebates.makerBps, "economic policy rebates.makerBps");
  member(policy.rebates.fundingSource, new Set(["none", "taker_fees", "house_subsidy"]), "economic policy rebates.fundingSource");

  const sources = resolveFlows(policy.sources, "sources");
  const sinks = resolveFlows(policy.sinks, "sinks");
  exactKeys(policy.reconciliation, ["tolerance", "requireBalancedTransfers", "competitionLedger", "houseLedger"], "economic policy reconciliation");
  decimal(policy.reconciliation.tolerance, "economic policy reconciliation.tolerance");
  boolean(policy.reconciliation.requireBalancedTransfers, "economic policy reconciliation.requireBalancedTransfers");
  boolean(policy.reconciliation.competitionLedger, "economic policy reconciliation.competitionLedger");
  boolean(policy.reconciliation.houseLedger, "economic policy reconciliation.houseLedger");

  const resolved = {
    schemaVersion: policy.schemaVersion,
    policyId: policy.policyId,
    version: policy.version,
    currency: policy.currency,
    competitionLedger: structuredClone(policy.competitionLedger),
    houseLedger: structuredClone(policy.houseLedger),
    fees: structuredClone(policy.fees),
    rebates: structuredClone(policy.rebates),
    sources,
    sinks,
    reconciliation: structuredClone(policy.reconciliation),
  };
  return { ...resolved, contentHash: canonicalHash(resolved) };
}

export function resolveScoringPolicy(policy) {
  object(policy, "scoring policy");
  exactKeys(policy, [
    "schemaVersion",
    "policyId",
    "version",
    "status",
    "formulaVersion",
    "baseline",
    "publicScoringEnabled",
    "eligibleActorClasses",
    "components",
    "penalties",
    "disqualification",
    "replayLock",
  ], "scoring policy");
  equal(policy.schemaVersion, "reef.arena.scoringPolicy.v1", "scoring policy schemaVersion");
  token(policy.policyId, "scoring policy policyId");
  token(policy.version, "scoring policy version");
  member(policy.status, new Set(["calibration", "public-preview"]), "scoring policy status");
  token(policy.formulaVersion, "scoring policy formulaVersion");
  nonnegativeInteger(policy.baseline, "scoring policy baseline");
  boolean(policy.publicScoringEnabled, "scoring policy publicScoringEnabled");
  array(policy.eligibleActorClasses, "scoring policy eligibleActorClasses");
  nonEmpty(policy.eligibleActorClasses, "scoring policy eligibleActorClasses");
  const eligibleActorClasses = [...uniqueTokens(policy.eligibleActorClasses, "scoring policy eligibleActorClasses")];
  eligibleActorClasses.forEach((actorClass) => member(actorClass, ACTOR_CLASSES, "scoring policy eligibleActorClasses"));

  const componentNames = ["equity", "risk", "conduct", "marketInteraction", "npcDifficulty"];
  object(policy.components, "scoring policy components");
  exactKeys(policy.components, componentNames, "scoring policy components");
  for (const name of componentNames) {
    const component = policy.components[name];
    object(component, `scoring policy components.${name}`);
    exactKeys(component, ["enabled", "cap"], `scoring policy components.${name}`);
    boolean(component.enabled, `scoring policy components.${name}.enabled`);
    nonnegativeInteger(component.cap, `scoring policy components.${name}.cap`);
  }

  object(policy.penalties, "scoring policy penalties");
  exactKeys(policy.penalties, ["freeze", "operationalPause", "invalidIntentCap"], "scoring policy penalties");
  Object.entries(policy.penalties).forEach(([key, value]) => nonnegativeInteger(value, `scoring policy penalties.${key}`));
  object(policy.disqualification, "scoring policy disqualification");
  exactKeys(policy.disqualification, ["freezeCount", "excludeFromLeaderboard"], "scoring policy disqualification");
  nonnegativeInteger(policy.disqualification.freezeCount, "scoring policy disqualification.freezeCount");
  boolean(policy.disqualification.excludeFromLeaderboard, "scoring policy disqualification.excludeFromLeaderboard");
  object(policy.replayLock, "scoring policy replayLock");
  exactKeys(policy.replayLock, ["from", "until", "requirePolicyEnvelopeHash"], "scoring policy replayLock");
  equal(policy.replayLock.from, "run_acceptance", "scoring policy replayLock.from");
  equal(policy.replayLock.until, "score_publication", "scoring policy replayLock.until");
  equal(policy.replayLock.requirePolicyEnvelopeHash, true, "scoring policy replayLock.requirePolicyEnvelopeHash");

  const resolved = structuredClone(policy);
  return { ...resolved, contentHash: canonicalHash(resolved) };
}

export function resolvePolicyComposition(mode, actorCatalog, economicPolicy, scoringPolicy) {
  object(mode, "Arena mode");
  equal(mode.schemaVersion, "reef.arena.mode.v1", "Arena mode schemaVersion");
  for (const field of ["modeId", "version", "scenarioId", "scoringPolicyVersion", "riskPolicyVersion"]) {
    token(mode[field], `Arena mode ${field}`);
  }
  integer(mode.seed, "Arena mode seed");
  token(mode.actorProfileCatalogPath, "Arena mode actorProfileCatalogPath");
  token(mode.economicPolicyPath, "Arena mode economicPolicyPath");
  token(mode.scoringPolicyPath, "Arena mode scoringPolicyPath");
  equal(mode.actorProfileCatalogVersion, actorCatalog.version, "Arena mode actorProfileCatalogVersion");
  equal(mode.economicPolicyVersion, economicPolicy.version, "Arena mode economicPolicyVersion");
  equal(mode.scoringPolicyVersion, scoringPolicy.version, "Arena mode scoringPolicyVersion");

  const resolved = {
    schemaVersion: "reef.arena.runComposition.v1",
    modeId: mode.modeId,
    modeVersion: mode.version,
    scenarioId: mode.scenarioId,
    seed: mode.seed,
    actorProfileCatalog: {
      catalogId: actorCatalog.catalogId,
      version: actorCatalog.version,
      contentHash: actorCatalog.contentHash,
    },
    riskPolicy: { version: mode.riskPolicyVersion },
    scoringPolicy: {
      policyId: scoringPolicy.policyId,
      version: scoringPolicy.version,
      contentHash: scoringPolicy.contentHash,
    },
    economicPolicy: {
      policyId: economicPolicy.policyId,
      version: economicPolicy.version,
      contentHash: economicPolicy.contentHash,
    },
  };
  return { ...resolved, compositionHash: canonicalHash(resolved) };
}

export function isCanonicalSha256(value) {
  return typeof value === "string" && SHA256_PATTERN.test(value);
}

function resolveFlows(flows, name) {
  array(flows, `economic policy ${name}`);
  const codes = new Set();
  return flows.map((flow, index) => {
    const path = `economic policy ${name}[${index}]`;
    object(flow, path);
    exactKeys(flow, ["code", "ledger", "enabled", "funding"], path);
    token(flow.code, `${path}.code`);
    if (codes.has(flow.code)) throw new Error(`duplicate economic policy ${name} code ${flow.code}`);
    codes.add(flow.code);
    member(flow.ledger, LEDGERS, `${path}.ledger`);
    boolean(flow.enabled, `${path}.enabled`);
    token(flow.funding, `${path}.funding`);
    return structuredClone(flow);
  });
}

function exactDecimalObject(value, keys, path, booleanKeys = []) {
  object(value, path);
  exactKeys(value, keys, path);
  for (const key of keys) {
    if (booleanKeys.includes(key)) boolean(value[key], `${path}.${key}`);
    else decimal(value[key], `${path}.${key}`);
  }
}

function exactKeys(value, allowedKeys, path, { allowMissing = false } = {}) {
  const allowed = new Set(allowedKeys);
  const unknown = Object.keys(value).filter((key) => !allowed.has(key));
  if (unknown.length > 0) throw new Error(`${path} has unknown field ${unknown[0]}`);
  if (!allowMissing) {
    const missing = allowedKeys.filter((key) => !Object.hasOwn(value, key));
    if (missing.length > 0) throw new Error(`${path} is missing field ${missing[0]}`);
  }
}

function uniqueTokens(values, path) {
  const result = new Set();
  values.forEach((value, index) => {
    token(value, `${path}[${index}]`);
    if (result.has(value)) throw new Error(`${path} contains duplicate ${value}`);
    result.add(value);
  });
  return result;
}

function assertJsonValue(value, path) {
  if (value === null || typeof value === "string" || typeof value === "boolean") return;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error(`${path} must not contain a non-finite number`);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry, index) => assertJsonValue(entry, `${path}[${index}]`));
    return;
  }
  if (typeof value === "object") {
    for (const [key, entry] of Object.entries(value)) {
      if (entry === undefined) throw new Error(`${path}.${key} must not be undefined`);
      assertJsonValue(entry, `${path}.${key}`);
    }
    return;
  }
  throw new Error(`${path} must contain only JSON values`);
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function object(value, path) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(`${path} must be an object`);
}

function array(value, path) {
  if (!Array.isArray(value)) throw new Error(`${path} must be an array`);
}

function nonEmpty(value, path) {
  if (value.length === 0) throw new Error(`${path} must not be empty`);
}

function token(value, path) {
  if (typeof value !== "string" || value.trim() !== value || value.length === 0) throw new Error(`${path} must be a non-empty trimmed string`);
}

function currency(value, path) {
  if (typeof value !== "string" || !/^[A-Z]{3}$/.test(value)) throw new Error(`${path} must be an ISO-style three-letter code`);
}

function decimal(value, path) {
  if (typeof value !== "string" || !/^(0|[1-9]\d*)(\.\d+)?$/.test(value)) throw new Error(`${path} must be a nonnegative canonical decimal string`);
}

function scalar(value, path) {
  if (!["string", "number", "boolean"].includes(typeof value) || (typeof value === "number" && !Number.isFinite(value))) {
    throw new Error(`${path} must be a finite scalar`);
  }
}

function boolean(value, path) {
  if (typeof value !== "boolean") throw new Error(`${path} must be true or false`);
}

function integer(value, path) {
  if (!Number.isSafeInteger(value)) throw new Error(`${path} must be a safe integer`);
}

function nonnegativeInteger(value, path) {
  integer(value, path);
  if (value < 0) throw new Error(`${path} must be nonnegative`);
}

function equal(actual, expected, path) {
  if (actual !== expected) throw new Error(`${path} must be ${expected}; got ${actual}`);
}

function member(value, allowed, path) {
  if (!allowed.has(value)) throw new Error(`${path} has unsupported value ${value}`);
}
