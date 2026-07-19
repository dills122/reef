import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();

const service = env("STREAM_PUBLISH_BENCH_SERVICE", "platform-api");
const mainClass = "com.reef.platform.tools.StreamPublishBenchKt";

console.log("stream publish bench settings:");
console.log(`  service=${service}`);
console.log(`  stream=${env("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS")}`);
console.log(`  rate=${env("STREAM_PUBLISH_BENCH_RATE", "10000")}`);
console.log(`  duration=${env("STREAM_PUBLISH_BENCH_DURATION", "60s")}`);
console.log(`  partitionMode=${env("STREAM_PUBLISH_BENCH_PARTITION_MODE", "round-robin")}`);
console.log(`  payloadBytes=${env("STREAM_PUBLISH_BENCH_PAYLOAD_BYTES", "768")}`);
console.log(`  reportOut=${env("STREAM_PUBLISH_BENCH_REPORT_OUT", "")}`);

const envArgs = Object.entries(process.env)
  .filter(([name]) => name.startsWith("STREAM_") || name.startsWith("MATCHING_ENGINE_"))
  .flatMap(([name, value]) => ["-e", `${name}=${value}`]);

await run("docker", [
  "compose",
  "-f",
  "compose.base.yml",
  "-f",
  "compose.local.yml",
  "exec",
  "-T",
  ...envArgs,
  service,
  "java",
  "-cp",
  "/app/platform-runtime/lib/*",
  mainClass,
]);
