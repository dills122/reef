import { deriveDevUrls, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();

const extraArgs = process.argv.slice(2);
const hasBaseUrl = extraArgs.includes("--base-url");

const args = ["run", "./cmd/load-tester"];
if (!hasBaseUrl) {
  args.push("--base-url", runtimeUrl);
}
args.push(...extraArgs);

await run("go", args, { cwd: "services/simulator" });
