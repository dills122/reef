import { execFile } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { promisify } from "node:util";

import { deriveDevUrls, env, loadDotEnv, run, setDefault, waitForHttp } from "./lib/dev-utils.mjs";
import { STACK_PROFILES, runStackDown, runStackReset, runStackUp } from "./lib/dev-stack-profiles.mjs";
import { STRESS_PROFILES, runStressProfile } from "./lib/dev-stress-profiles.mjs";
import { composeArgs, composeFiles } from "./lib/compose-utils.mjs";
import { LINK_TARGETS, runLinks } from "./lib/dev-links.mjs";
import { printStreamProfileSummary, streamProfileNames, validateStreamProfile } from "./lib/stream-profile-guard.mjs";
import { aggregateReports } from "./lib/report-taxonomy.mjs";

const args = process.argv.slice(2);
const execFileAsync = promisify(execFile);

try {
  await main(args);
} catch (error) {
  console.error(error?.message ?? error);
  process.exit(1);
}

async function main(argv) {
  const [group, command, profile, ...rest] = argv;

  if (!group || group === "help" || group === "--help" || group === "-h") {
    printUsage();
    return;
  }

  if (group === "stack") {
    await runStackCommand(command, profile, rest);
    return;
  }

  if (group === "stress") {
    await runStressCommand(command, profile);
    return;
  }

  if (group === "links") {
    await runLinksCommand(command, [profile, ...rest].filter(Boolean));
    return;
  }

  if (group === "stream") {
    runStreamCommand(command, profile);
    return;
  }

  if (group === "sim") {
    await runSimCommand(command, [profile, ...rest].filter(Boolean));
    return;
  }

  if (group === "list") {
    printList();
    return;
  }

  await runAlias(group, command);
}

async function runStackCommand(command, profile, rest = []) {
  switch (command) {
    case "up":
      profile = profile ?? "default";
      assertKnownProfile(profile, STACK_PROFILES, "stack");
      await runStackUp(profile);
      return;
    case "down":
      await runStackDown();
      return;
    case "reset":
      await runStackReset();
      return;
    case "compose-config":
      loadDotEnv();
      console.log(`compose files: ${composeFiles().join(", ")}`);
      await run("docker", composeArgs(["config", ...[profile, ...rest].filter(Boolean)]));
      return;
    case "compose-parity":
      await runComposeParity();
      return;
    default:
      throw new Error(`unknown stack command: ${command ?? ""}`);
  }
}

async function runStressCommand(command, profile) {
  if (command !== "run") {
    throw new Error(`unknown stress command: ${command ?? ""}`);
  }
  assertKnownProfile(profile, STRESS_PROFILES, "stress");
  await runStressProfile(profile);
}

async function runLinksCommand(target, args = []) {
  assertKnownProfile(target, LINK_TARGETS, "link target");
  await runLinks(target, { dryRun: args.includes("--dry-run") });
}

function runStreamCommand(command, profile) {
  if (command !== "validate") {
    throw new Error(`unknown stream command: ${command ?? ""}`);
  }
  loadDotEnv();
  profile = profile || env("DEV_STREAM_PROFILE", "stream-direct-nodb");
  assertKnownProfile(profile, new Set(streamProfileNames), "stream profile");
  applyStreamProfileValidationDefaults(profile);
  validateStreamProfile(profile);
  printStreamProfileSummary(profile);
  console.log(`stream profile ${profile} ok`);
}

async function runSimCommand(command, args = []) {
  switch (command) {
    case "run":
      await runSimulator(args);
      return;
    case "batch":
      await runSimulatorBatch(args);
      return;
    default:
      throw new Error(`unknown sim command: ${command ?? ""}`);
  }
}

async function runAlias(alias, maybeProfile) {
  switch (alias) {
    case "up":
      await runStackUp("default");
      return;
    case "down":
      await runStackDown();
      return;
    case "reset":
      await runStackReset();
      return;
    case "runtime-nodb-up":
      await runStackUp("runtime-nodb");
      return;
    case "captured-ack-up":
      await runStackUp("captured-ack");
      return;
    case "stream-ack-up":
      await runStackUp("stream-ack");
      return;
    case "stream-direct-nodb-up":
      await runStackUp("stream-direct-nodb");
      return;
    case "runtime-nodb-stress":
      await runStressProfile("runtime-nodb");
      return;
    case "captured-ack-stress":
      await runStressProfile("captured-ack");
      return;
    case "stream-ack-stress":
      await runStressProfile("stream-ack");
      return;
    case "stream-direct-nodb-stress":
      await runStressProfile("stream-direct-nodb");
      return;
    case "codex-links":
      await runLinks("codex");
      return;
    case "claude-links":
      await runLinks("claude");
      return;
    case "stress":
      assertKnownProfile(maybeProfile, STRESS_PROFILES, "stress");
      await runStressProfile(maybeProfile);
      return;
    default:
      throw new Error(`unknown reef-dev command: ${alias}`);
  }
}

function assertKnownProfile(profile, profiles, family) {
  if (profiles.has(profile)) return;
  throw new Error(`unknown ${family} profile: ${profile ?? ""}`);
}

function printUsage() {
  console.log(`usage:
  bun scripts/dev/reef-dev.mjs stack up [default|runtime-nodb|captured-ack|stream-ack|stream-direct-nodb]
  bun scripts/dev/reef-dev.mjs stack down
  bun scripts/dev/reef-dev.mjs stack reset
  bun scripts/dev/reef-dev.mjs stack compose-config [docker-compose-config-args]
  bun scripts/dev/reef-dev.mjs stack compose-parity
  bun scripts/dev/reef-dev.mjs stress run [runtime-nodb|captured-ack|stream-ack|stream-direct-nodb]
  bun scripts/dev/reef-dev.mjs links [codex|claude] [--dry-run]
  bun scripts/dev/reef-dev.mjs stream validate [${streamProfileNames.join("|")}]
  bun scripts/dev/reef-dev.mjs sim run [load-tester-args]
  bun scripts/dev/reef-dev.mjs sim batch [load-tester-args]
  bun scripts/dev/reef-dev.mjs list

compat aliases:
  up, down, reset, runtime-nodb-up, captured-ack-up, stream-ack-up, stream-direct-nodb-up
  runtime-nodb-stress, captured-ack-stress, stream-ack-stress, stream-direct-nodb-stress
  codex-links, claude-links`);
}

function printList() {
  console.log("reef-dev commands:");
  console.log(`  stack profiles: ${[...STACK_PROFILES].join(", ")}`);
  console.log("  stack commands: up, down, reset, compose-config, compose-parity");
  console.log(`  stress profiles: ${[...STRESS_PROFILES].join(", ")}`);
  console.log("  sim commands: run, batch");
  console.log(`  stream validation profiles: ${streamProfileNames.join(", ")}`);
  console.log(`  link targets: ${[...LINK_TARGETS].join(", ")}`);
  console.log("  aliases: up, down, reset, runtime-nodb-up, captured-ack-up, stream-ack-up, stream-direct-nodb-up");
  console.log("  aliases: runtime-nodb-stress, captured-ack-stress, stream-ack-stress, stream-direct-nodb-stress");
  console.log("  aliases: codex-links, claude-links");
}

async function runSimulator(extraArgs) {
  loadDotEnv();
  const { runtimeUrl } = deriveDevUrls();
  const commandProcessingMode = env("DEV_SIM_COMMAND_PROCESSING_MODE", "");
  const commandLogMode = env(
    "DEV_SIM_COMMAND_LOG_MODE",
    commandProcessingMode && commandProcessingMode !== "sync-result" ? "postgres" : "",
  );

  if (commandProcessingMode) {
    const previousProcessingMode = process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE;
    const previousCommandLogMode = process.env.EXTERNAL_API_COMMAND_LOG_MODE;
    process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE = commandProcessingMode;
    if (commandLogMode) {
      process.env.EXTERNAL_API_COMMAND_LOG_MODE = commandLogMode;
    }
    try {
      await run("docker", composeArgs(["up", "-d", "platform-api"]));
      await waitForHttp(`${runtimeUrl}/health`, 90, 2000);
    } finally {
      restoreEnv("EXTERNAL_API_COMMAND_PROCESSING_MODE", previousProcessingMode);
      restoreEnv("EXTERNAL_API_COMMAND_LOG_MODE", previousCommandLogMode);
    }
  }

  await run("go", loadTesterArgs(extraArgs, runtimeUrl), { cwd: "services/simulator" });
}

async function runSimulatorBatch(extraArgs) {
  loadDotEnv();
  const { runtimeUrl } = deriveDevUrls();

  const seeds = parseSeeds(env("DEV_SIM_BATCH_SEEDS", "101,202,303"));
  const artifactDir = resolve(env("DEV_SIM_BATCH_ARTIFACT_DIR", "/tmp/reef-sim-batch"));
  const aggregateOut = resolve(env("DEV_SIM_BATCH_REPORT_OUT", `${artifactDir}/aggregate.json`));

  if (extraArgs.includes("--report-out")) {
    throw new Error("do not pass --report-out to sim batch; set DEV_SIM_BATCH_ARTIFACT_DIR or DEV_SIM_BATCH_REPORT_OUT");
  }
  if (extraArgs.includes("--seed")) {
    throw new Error("do not pass --seed to sim batch; set DEV_SIM_BATCH_SEEDS");
  }

  mkdirSync(artifactDir, { recursive: true });

  const reports = [];
  for (const seed of seeds) {
    const reportOut = resolve(artifactDir, `seed-${seed}.report.json`);
    const args = [...loadTesterArgs(extraArgs, runtimeUrl), "--seed", String(seed), "--report-out", reportOut];
    console.log(`running simulator seed ${seed}`);
    await run("go", args, { cwd: "services/simulator" });
    reports.push({ path: reportOut, data: JSON.parse(readFileSync(reportOut, "utf8")) });
  }

  const aggregate = {
    generatedAt: new Date().toISOString(),
    artifactDir,
    seeds,
    ...aggregateReports(reports),
  };
  writeFileSync(aggregateOut, JSON.stringify(aggregate, null, 2));
  console.log(`aggregate: ${aggregateOut}`);
}

function loadTesterArgs(extraArgs, runtimeUrl) {
  const args = ["run", "./cmd/load-tester"];
  if (!extraArgs.includes("--base-url")) {
    args.push("--base-url", runtimeUrl);
  }
  args.push(...extraArgs);
  return args;
}

function parseSeeds(raw) {
  const out = raw
    .split(",")
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isInteger(value) && value !== 0);
  if (out.length === 0) {
    throw new Error("DEV_SIM_BATCH_SEEDS must contain at least one non-zero integer seed");
  }
  return out;
}

function restoreEnv(name, value) {
  if (value == null) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}

async function runComposeParity() {
  const monolithArgs = ["compose", "-f", "docker-compose.yml", "config"];
  const layeredArgs = ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml", "config"];

  const monolith = await dockerComposeConfig(monolithArgs);
  const layered = await dockerComposeConfig(layeredArgs);

  if (monolith !== layered) {
    console.error("Compose config parity failed: docker-compose.yml differs from compose.base.yml + compose.local.yml.");
    console.error("Run this for details:");
    console.error("  docker compose -f docker-compose.yml config > /tmp/reef-compose-monolith.yml");
    console.error("  docker compose -f compose.base.yml -f compose.local.yml config > /tmp/reef-compose-layered.yml");
    console.error("  diff -u /tmp/reef-compose-monolith.yml /tmp/reef-compose-layered.yml");
    process.exit(1);
  }

  console.log("Compose config parity OK: docker-compose.yml matches compose.base.yml + compose.local.yml.");
}

async function dockerComposeConfig(composeArgv) {
  const { stdout } = await execFileAsync("docker", composeArgv, {
    cwd: process.cwd(),
    maxBuffer: 20 * 1024 * 1024,
  });
  return stdout;
}

function applyStreamProfileValidationDefaults(profileName) {
  setDefault("RUNTIME_PERSISTENCE", "noop");
  setDefault("EXTERNAL_API_IDEMPOTENCY_STORE", "inmemory");
  setDefault("EXTERNAL_API_COMMAND_CAPTURE_MODE", "disabled");
  setDefault("EXTERNAL_API_COMMAND_LOG_MODE", "disabled");
  setDefault("STREAM_ACK_INTAKE_STORE", "inmemory");
  setDefault("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES", "100000");
  setDefault("STREAM_ACK_INMEMORY_INTAKE_SHARDS", "256");
  setDefault("PLATFORM_HTTP_SERVER", "netty");
  setDefault("MATCHING_ENGINE_DIRECT_STREAM_ENABLED", "true");
  setDefault("STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "true");
  setDefault("STREAM_ACK_WORKER_ENABLED", "false");
  setDefault("STREAM_ACK_PROJECTOR_ENABLED", "false");
  setDefault("EXTERNAL_API_ABUSE_BREAKER_MODE", "off");

  if (profileName === "noop-ceiling") {
    setDefault("STREAM_ACK_PUBLISHER", "noop");
  }

  if (profileName === "materializer-soak") {
    setDefault("STREAM_ACK_LOG_PROVIDER", "redpanda");
    setDefault("VENUE_EVENT_MATERIALIZER_ENABLED", "true");
    setDefault("DEV_STRESS_CAPTURE_STREAM_DIRECT", "1");
    setDefault("DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER", "1");
    setDefault("DEV_STRESS_FAIL_ON_STREAM_DIRECT_FAILURES", "1");
    setDefault("DEV_STRESS_FAIL_ON_VENUE_EVENT_MATERIALIZER_FAILURES", "1");
    setDefault("DEV_STRESS_MAX_STREAM_DIRECT_COMPLETION_GAP", "0");
    setDefault("DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_COMPLETION_GAP", "0");
    setDefault("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", "1000");
    setDefault("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", "250000");
  }
}
