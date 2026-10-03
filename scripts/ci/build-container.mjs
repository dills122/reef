import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { pathToFileURL } from "node:url";

// Match Gradle's failed request and response together, not an arbitrary 429
// printed by application code, tests, or another package repository.
export function isMavenRateLimit(log) {
  return /Could not (?:GET|HEAD) 'https:\/\/(?:repo\.maven\.apache\.org\/maven2|plugins\.gradle\.org\/m2)\/[^'\r\n]+'[\s\S]{0,500}?Received status code 429\b/.test(log);
}

export function runCommand(command, args) {
  return new Promise((resolve, reject) => {
    let log = "";
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"] });
    const capture = (stream) => (chunk) => {
      stream.write(chunk);
      log = (log + chunk.toString()).slice(-65536);
    };
    child.stdout.on("data", capture(process.stdout));
    child.stderr.on("data", capture(process.stderr));
    child.on("error", reject);
    child.on("close", (code, signal) => resolve({ code, signal, log }));
  });
}

export async function buildContainer(args, {
  run = (buildArgs) => runCommand("docker", ["build", "--progress=plain", ...buildArgs]),
  wait = sleep,
  report = console.error,
} = {}) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const result = await run(args);
    if (result.code === 0) return 0;
    if (result.signal || !isMavenRateLimit(result.log) || attempt === 3) {
      return result.code || 1;
    }
    const delayMs = attempt * 15000;
    report(`Maven dependency request throttled (HTTP 429); retrying Docker build in ${delayMs / 1000}s (attempt ${attempt + 1}/3).`);
    await wait(delayMs);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = await buildContainer(process.argv.slice(2));
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  }
}
