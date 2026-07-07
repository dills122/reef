import { loadDotEnv, run } from "./lib/dev-utils.mjs";
import { composeArgs, composeFiles } from "./lib/compose-utils.mjs";

loadDotEnv();

console.log(`compose files: ${composeFiles().join(", ")}`);
await run("docker", composeArgs(["config", ...process.argv.slice(2)]));
