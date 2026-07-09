import { env, loadDotEnv, run, setDefault } from "./lib/dev-utils.mjs";
import { STACK_PROFILES, runStackDown, runStackReset, runStackUp } from "./lib/dev-stack-profiles.mjs";
import { STRESS_PROFILES, runStressProfile } from "./lib/dev-stress-profiles.mjs";
import { composeArgs, composeFiles } from "./lib/compose-utils.mjs";
import { LINK_TARGETS, runLinks } from "./lib/dev-links.mjs";
import { printStreamProfileSummary, streamProfileNames, validateStreamProfile } from "./lib/stream-profile-guard.mjs";

const args = process.argv.slice(2);

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
  bun scripts/dev/reef-dev.mjs stress run [runtime-nodb|captured-ack|stream-ack|stream-direct-nodb]
  bun scripts/dev/reef-dev.mjs links [codex|claude] [--dry-run]
  bun scripts/dev/reef-dev.mjs stream validate [${streamProfileNames.join("|")}]
  bun scripts/dev/reef-dev.mjs list

compat aliases:
  up, down, reset, runtime-nodb-up, captured-ack-up, stream-ack-up, stream-direct-nodb-up
  runtime-nodb-stress, captured-ack-stress, stream-ack-stress, stream-direct-nodb-stress
  codex-links, claude-links`);
}

function printList() {
  console.log("reef-dev commands:");
  console.log(`  stack profiles: ${[...STACK_PROFILES].join(", ")}`);
  console.log("  stack commands: up, down, reset, compose-config");
  console.log(`  stress profiles: ${[...STRESS_PROFILES].join(", ")}`);
  console.log(`  stream validation profiles: ${streamProfileNames.join(", ")}`);
  console.log(`  link targets: ${[...LINK_TARGETS].join(", ")}`);
  console.log("  aliases: up, down, reset, runtime-nodb-up, captured-ack-up, stream-ack-up, stream-direct-nodb-up");
  console.log("  aliases: runtime-nodb-stress, captured-ack-stress, stream-ack-stress, stream-direct-nodb-stress");
  console.log("  aliases: codex-links, claude-links");
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
