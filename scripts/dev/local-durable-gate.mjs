import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

// Composed entrypoint for WORK_PLAN.md "Active Now" item 3, "Run short local
// durable gates before any long soak." Each of the 6 named gates is proven
// against one local Docker Compose Redpanda direct-stream materializer stack:
//
//   1. durable publish acknowledgement succeeds before 202.
//   2. direct engine consumer fetches/processes/publishes/acks all accepted commands.
//   3. materializer writes canonical outcomes for all accepted commands after drain.
//   4. replay/checksum reports 0 gaps, duplicate inserts, payload mismatches,
//      stream gaps, or overlaps.
//   5. projection replay/idempotency applies no duplicate read-model rows.
//   6. bot/user read-surface claims match /api/v1/data/availability and the
//      read-surface inventory in docs/TRADING_MARKET_DATA_BOUNDARIES.md.
//
// Gates 1-3 and 5 are proven by scripts/dev/venue-event-materializer-smoke.mjs
// (which also brings the stack up): it submits one order through the
// stream-ack no-DB intake API, waits for the durable canonical command
// outcome, waits for background projection to catch up, and (gate 5) rewinds
// the live projection watermark and proves replay does not duplicate
// read-model rows. Gate 4 is scripts/dev/venue-event-replay-check.mjs scoped
// to the same event stream/projection the smoke run used. Gate 6 is
// scripts/dev/read-surface-availability-check.mjs against the same stack's
// read API.
const jsRuntime = env("DEV_LOCAL_DURABLE_GATE_JS_RUNTIME", process.execPath);
const artifactDir = env("DEV_LOCAL_DURABLE_GATE_ARTIFACT_DIR", "/tmp/reef-local-durable-gate");
const gateId = env("DEV_LOCAL_DURABLE_GATE_ID", `local-durable-gate-${Date.now()}`);
const streamToken = gateId.replace(/[^A-Za-z0-9_-]/g, "_");
// Must mirror scripts/dev/venue-event-materializer-smoke.mjs's own default
// naming so gate 4's replay-check scopes to the exact event stream/projection
// the smoke run (gates 1-3, 5) produced.
const projectionName = `runtime-normalized-venue-outcomes-${gateId}`;
const eventStream = `REEF_MATERIALIZER_SMOKE_VENUE_EVENTS_${streamToken.toUpperCase()}`;
const readApiUrl = env(
  "DEV_VENUE_EVENT_MATERIALIZER_READ_API_URL",
  `http://127.0.0.1:${env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", "8084")}`,
);

mkdirSync(artifactDir, { recursive: true });

process.env.DEV_VENUE_EVENT_MATERIALIZER_SMOKE_ID = gateId;
process.env.DEV_VENUE_EVENT_MATERIALIZER_PROJECTION_NAME = projectionName;

const gates = [];

await runGate({
  ids: ["1", "2", "3", "5"],
  label: "durable-publish-ack + direct-engine-consume + materializer-write + projection-replay-idempotency",
  script: "venue-event-materializer-smoke.mjs",
  env: {},
});

await runGate({
  ids: ["4"],
  label: "replay/checksum: 0 gaps, duplicate inserts, payload mismatches, stream gaps/overlaps",
  script: "venue-event-replay-check.mjs",
  env: {
    DEV_VENUE_EVENT_REPLAY_CHECK_PROJECTION_NAME: projectionName,
    DEV_VENUE_EVENT_REPLAY_CHECK_EVENT_STREAM: eventStream,
  },
});

await runGate({
  ids: ["6"],
  label: "read-surface claims match /api/v1/data/availability and TRADING_MARKET_DATA_BOUNDARIES.md",
  script: "read-surface-availability-check.mjs",
  env: {
    DEV_READ_SURFACE_CHECK_READ_API_URL: readApiUrl,
    DEV_READ_SURFACE_CHECK_VENUE_PROJECTION_NAME: projectionName,
    DEV_READ_SURFACE_CHECK_MARKET_DATA_PROJECTION_NAME: `market-data-top-of-book-${gateId}`,
    DEV_READ_SURFACE_CHECK_SOURCE: "venue-event-batch",
  },
});

const summary = {
  gateId,
  checkedAt: new Date().toISOString(),
  pass: gates.every((gate) => gate.pass),
  gates,
};
const summaryPath = join(artifactDir, `${gateId}-summary.json`);
writeFileSync(summaryPath, JSON.stringify(summary, null, 2));

console.log("");
console.log("=== local durable gate summary ===");
for (const gate of gates) {
  console.log(`[${gate.pass ? "PASS" : "FAIL"}] gate(s) ${gate.ids.join(",")}: ${gate.label} (${gate.durationMs}ms)`);
  if (!gate.pass) {
    console.log(`       ${gate.error}`);
  }
}
console.log(`summary: ${summaryPath}`);

if (!summary.pass) {
  console.error("local durable gate FAILED");
  process.exitCode = 1;
} else {
  console.log("local durable gate PASSED");
}

async function runGate({ ids, label, script, env: extraEnv }) {
  console.log("");
  console.log(`--- running gate(s) ${ids.join(",")}: ${label} (${script}) ---`);
  const startedAt = Date.now();
  const childEnv = { ...process.env, ...extraEnv };
  try {
    await runInherit(jsRuntime, [`scripts/dev/${script}`], childEnv);
    gates.push({ ids, label, script, pass: true, durationMs: Date.now() - startedAt });
  } catch (error) {
    gates.push({
      ids,
      label,
      script,
      pass: false,
      durationMs: Date.now() - startedAt,
      error: error instanceof Error ? error.message : String(error),
    });
  }
}

function runInherit(cmd, args, envVars) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { stdio: "inherit", env: envVars });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`${cmd} ${args.join(" ")} exited with code ${code}`));
      }
    });
  });
}
