import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const profiles = env("DEV_COMPOSE_PROFILES", "");
const waitTimeoutSeconds = env("DEV_COMPOSE_WAIT_TIMEOUT_SECONDS", "300");
const runSmoke = env("DEV_RESET_RUN_SMOKE", "0") === "1";
const smokeWaitTimeoutSeconds = env("DEV_RESET_SMOKE_WAIT_TIMEOUT_SECONDS", "45");
if (profiles) {
  process.env.COMPOSE_PROFILES = profiles;
}

console.log("stopping stack and removing volumes...");
await run("docker", ["compose", "down", "--volumes", "--remove-orphans"]);

console.log("starting stack...");
await run("docker", ["compose", "up", "-d", "--build", "--wait", "--wait-timeout", waitTimeoutSeconds]);

if (runSmoke) {
  console.log(`running smoke verification (timeout=${smokeWaitTimeoutSeconds}s)...`);
  const jsRuntime = env("JS_RUNTIME", "bun");
  if (!process.env.DEV_WAIT_TIMEOUT_SECONDS) {
    process.env.DEV_WAIT_TIMEOUT_SECONDS = smokeWaitTimeoutSeconds;
  }
  await run(jsRuntime, ["scripts/dev/smoke.mjs"]);
} else {
  console.log("skipping smoke verification (set DEV_RESET_RUN_SMOKE=1 to enable)");
}
