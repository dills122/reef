import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

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

async function dockerComposeConfig(args) {
  const { stdout } = await execFileAsync("docker", args, {
    cwd: process.cwd(),
    maxBuffer: 20 * 1024 * 1024,
  });
  return stdout;
}
