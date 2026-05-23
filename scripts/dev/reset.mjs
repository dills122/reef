import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const profiles = env("DEV_COMPOSE_PROFILES", "");
if (profiles) {
  process.env.COMPOSE_PROFILES = profiles;
}

console.log("stopping stack and removing volumes...");
await run("docker", ["compose", "down", "--volumes", "--remove-orphans"]);

console.log("starting stack...");
await run("docker", ["compose", "up", "-d", "--build"]);

console.log("running smoke verification...");
await run("bun", ["run", "scripts/dev/smoke.mjs"]);
