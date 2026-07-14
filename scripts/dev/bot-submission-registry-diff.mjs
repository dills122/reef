import { env, loadDotEnv } from "./lib/dev-utils.mjs";
import { assertValidBotId } from "./lib/bot-submission-contract.mjs";

loadDotEnv();

const [, , botId, ...flags] = process.argv;
const validateOnly = flags.includes("--validate-only");
if (!botId) {
  console.error("usage: node scripts/dev/bot-submission-registry-diff.mjs <botId> [--validate-only]");
  process.exit(1);
}
try {
  assertValidBotId(botId);
} catch (error) {
  console.error(`bot-submission-registry-diff: ${error.message}`);
  process.exit(1);
}
if (validateOnly) {
  console.log(`bot-submission-registry-diff: botId=${botId} valid`);
  process.exit(0);
}

// Must point at the real, always-on hosted admin API, not a per-run
// `make dev-up` stack: an ephemeral stack's registry DB is empty on every
// run, so every bot would incorrectly look "new". See bot-submission.yml's
// header comment for why this job does not start a local stack.
const adminApiUrl = env("ARENA_ADMIN_API_URL", "");
if (!adminApiUrl) {
  console.error("bot-submission-registry-diff: ARENA_ADMIN_API_URL is required (real hosted admin API, not localhost)");
  process.exit(1);
}
const adminApiToken = env("ARENA_ADMIN_API_TOKEN", "");
const actorId = env("ADMIN_ACTOR_ID", "bot-submission-ci");

async function lookupBot(id) {
  const response = await fetch(
    `${adminApiUrl}/admin/v1/arena/bots?botId=${encodeURIComponent(id)}`,
    {
      headers: {
        "X-Reef-Actor-Id": actorId,
        ...(adminApiToken ? { authorization: `Bearer ${adminApiToken}` } : {}),
      },
    },
  );
  if (response.status === 404) {
    return null;
  }
  if (response.status === 503) {
    throw new Error("arena admin service unavailable (set PLATFORM_ARENA_ADMIN_ENABLED=1 on the runtime)");
  }
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`GET /admin/v1/arena/bots failed (${response.status}): ${await response.text()}`);
  }
  return response.json();
}

const existing = await lookupBot(botId);

// Consumed by the calling CI job via $GITHUB_OUTPUT to branch between the
// new-bot (provision OpenBao identity+slice) and update-bot (reuse slice) paths.
const flow = existing ? "update" : "add";
console.log(`bot-submission-registry-diff: botId=${botId} flow=${flow}`);

const githubOutput = process.env.GITHUB_OUTPUT;
if (githubOutput) {
  const { appendFileSync } = await import("node:fs");
  appendFileSync(githubOutput, `flow=${flow}\n`);
}
