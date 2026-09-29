#!/usr/bin/env node
/** Accept immutable benchmark controls before source load; never write mutable settlement rows. */
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const ID = /^[A-Za-z0-9_-]+$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ENDPOINT = "/internal/admin/settlement/controls";

export function buildControlSeedPlan(fixture, options) {
  const { runId, eventStream, sourceGeneration, partitionCount, openingAmount, policy } = options;
  for (const [name, value] of [["runId", runId], ["eventStream", eventStream]]) {
    if (!ID.test(value ?? "")) throw new Error(`${name} must be a simple nonempty ID`);
  }
  if (!UUID.test(sourceGeneration ?? "")) throw new Error("sourceGeneration must be a UUID");
  if (!Number.isInteger(partitionCount) || partitionCount < 1 || partitionCount > 32768) {
    throw new Error("partitionCount must be between 1 and 32768");
  }
  if (!/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(openingAmount ?? "") ||
      !/[1-9]/.test(openingAmount)) {
    throw new Error("openingAmount must be an explicit positive decimal");
  }
  const session = one(fixture, /^  name: ([A-Za-z0-9_-]+)\s*$/gm, "session.name");
  const fixtureRun = one(fixture, /^  scenarioRunId: ([A-Za-z0-9_-]+)\s*$/gm,
    "session.scenarioRunId");
  if (fixtureRun !== runId) throw new Error("fixture scenarioRunId differs from requested runId");
  const actors = unique(fixture, /^\s+- actorId: ([A-Za-z0-9_-]+)\s*$/gm, "actors");
  const instruments = unique(fixture, /^\s+instrumentId: ([A-Za-z0-9_-]+)\s*$/gm,
    "instruments");
  const required = ["profileId", "policyVersion", "mode", "settlementCycle",
    "nettingMode", "ledgerPostingMode", "selectionSource"];
  for (const field of required) {
    if (String(policy?.[field] ?? "").trim() === "") {
      throw new Error(`explicit policy.${field} is required`);
    }
  }
  if (policy.profileId !== "instant-post-trade-v1" || policy.policyVersion !== 1 ||
      policy.mode !== "instant-post-trade" || policy.settlementCycle !== "T+0" ||
      policy.nettingMode !== "gross-or-microbatch" ||
      policy.ledgerPostingMode !== "near-instant-finality") {
    throw new Error("candidate benchmark requires explicit D-050 instant-post-trade-v1 policy tuple");
  }
  const sourceFrontiers = Array.from({ length: partitionCount }, (_, partitionId) => ({
    eventStream, sourceGeneration, partitionId: String(partitionId),
    sequence: (BigInt(partitionId) << 48n).toString(),
  }));
  const controls = [{
    kind: "POLICY", controlId: `benchmark-${runId}-policy-${session}`,
    runId, venueSessionId: session, sourceFrontiers,
    ...policy, policyVersion: String(policy.policyVersion),
  }];
  for (const actor of actors) {
    const participantId = `${actor}-participant`;
    const accountId = `${actor}-account`;
    for (const [assetType, assetId] of [["CASH", "USD"],
      ...instruments.map((instrument) => ["SECURITY", instrument])]) {
      controls.push({ kind: "OPENING",
        controlId: `benchmark-${runId}-opening-${actor}-${assetType}-${assetId}`,
        runId, participantId, accountId, assetType, assetId, amount: openingAmount });
    }
  }
  return { runId, venueSessionId: session, eventStream, sourceGeneration,
    actors: actors.length, instruments: instruments.length,
    expectedControlSequence: controls.length, controls };
}

function one(fixture, expression, name) {
  const values = [...fixture.matchAll(expression)].map((match) => match[1]);
  if (values.length !== 1) throw new Error(`fixture requires one ${name}`);
  return values[0];
}

function unique(fixture, expression, name) {
  const values = [...fixture.matchAll(expression)].map((match) => match[1]);
  if (values.length === 0 || new Set(values).size !== values.length) {
    throw new Error(`fixture requires distinct nonempty ${name}`);
  }
  return values;
}

function sourceGenerationFromRuntime() {
  const result = spawnSync("docker", ["compose", "-f", "compose.base.yml", "-f",
    "compose.local.yml", "exec", "-T", "postgres", "psql", "-X", "-A", "-t",
    "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef", "-c",
    "SELECT generation::text FROM runtime.postmatch_source_generation WHERE singleton = TRUE"],
  { encoding: "utf8", maxBuffer: 64 * 1024 });
  if (result.status !== 0) {
    throw new Error(result.stderr?.trim() || result.error?.message || "source generation read failed");
  }
  return result.stdout.trim();
}

export async function submitControlSeedPlan(plan, { runtimeUrl, adminToken, fetchImpl = fetch }) {
  let target;
  try { target = new URL(runtimeUrl); } catch { throw new Error("runtimeUrl must be loopback HTTP"); }
  if (target.protocol !== "http:" ||
      !["127.0.0.1", "localhost", "[::1]"].includes(target.hostname) ||
      target.username || target.password || target.pathname !== "/" ||
      target.search || target.hash) {
    throw new Error("runtimeUrl must be unauthenticated loopback HTTP origin");
  }
  if (!adminToken) throw new Error("ADMIN_API_TOKEN is required for control acceptance");
  let duplicates = 0;
  for (const [index, control] of plan.controls.entries()) {
    const response = await fetchImpl(new URL(ENDPOINT, target).href, {
      method: "POST", headers: { "content-type": "application/json",
        Authorization: `Bearer ${adminToken}`, "X-Reef-Actor-Id": "settlement-candidate-seed" },
      redirect: "error",
      body: JSON.stringify(control),
    });
    const body = await response.json();
    if (!response.ok || body.status !== "accepted" ||
        body.controlId !== control.controlId || body.controlSequence !== index + 1 ||
        typeof body.duplicate !== "boolean") {
      throw new Error(`control ${index + 1}/${plan.controls.length} rejected or out of order: ` +
        JSON.stringify({ httpStatus: response.status, controlId: control.controlId, response: body }));
    }
    if (body.duplicate) duplicates++;
  }
  return { status: "accepted", runId: plan.runId, eventStream: plan.eventStream,
    sourceGeneration: plan.sourceGeneration, actors: plan.actors,
    instruments: plan.instruments, expectedControlSequence: plan.expectedControlSequence,
    duplicates };
}

async function main() {
  const [fixturePath, runId, ...flags] = process.argv.slice(2);
  if (!fixturePath || !runId) throw new Error("usage: settlement-candidate-control-seed.mjs FIXTURE_YAML RUN_ID [--dry-run] [--source-generation=UUID]");
  const dryRun = flags.includes("--dry-run");
  const generationFlag = flags.find((flag) => flag.startsWith("--source-generation="));
  if (flags.some((flag) => flag !== "--dry-run" && flag !== generationFlag)) {
    throw new Error("unknown control seed flag");
  }
  const required = (name) => {
    const value = process.env[name];
    if (!value) throw new Error(`${name} must be set explicitly`);
    return value;
  };
  const fixture = readFileSync(fixturePath, "utf8");
  const plan = buildControlSeedPlan(fixture, {
    runId, eventStream: required("MATCHING_ENGINE_EVENT_STREAM"),
    sourceGeneration: generationFlag?.slice("--source-generation=".length) ??
      process.env.POSTMATCH_SOURCE_GENERATION ?? sourceGenerationFromRuntime(),
    partitionCount: Number(required("STREAM_ACK_PARTITION_COUNT")),
    openingAmount: required("SETTLEMENT_CANDIDATE_OPENING_AMOUNT"),
    policy: { profileId: required("SETTLEMENT_CANDIDATE_POLICY_PROFILE_ID"),
      policyVersion: Number(required("SETTLEMENT_CANDIDATE_POLICY_VERSION")),
      mode: required("SETTLEMENT_CANDIDATE_POLICY_MODE"),
      settlementCycle: required("SETTLEMENT_CANDIDATE_POLICY_CYCLE"),
      nettingMode: required("SETTLEMENT_CANDIDATE_POLICY_NETTING_MODE"),
      ledgerPostingMode: required("SETTLEMENT_CANDIDATE_POLICY_LEDGER_POSTING_MODE"),
      selectionSource: required("SETTLEMENT_CANDIDATE_POLICY_SELECTION_SOURCE") },
  });
  if (dryRun) {
    console.log(JSON.stringify(plan));
    return;
  }
  const result = await submitControlSeedPlan(plan, {
    runtimeUrl: required("SETTLEMENT_CANDIDATE_CONTROL_RUNTIME_URL"),
    adminToken: required("ADMIN_API_TOKEN"),
  });
  console.log(JSON.stringify(result));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main();
}
