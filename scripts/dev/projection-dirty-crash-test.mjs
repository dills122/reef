import { spawn } from "node:child_process";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const container = `reef-f02-crash-${process.pid}-${Date.now()}`;
const port = process.env.REEF_F02_CRASH_TEST_PORT ?? "25437";
const migrationDomains = ["runtime", "auth", "admin", "command_log"];
const testName =
  "com.reef.platform.infrastructure.persistence.PostgresVenueEventBatchMaterializationIntegrationTest.retainsDirtyWorkAcrossUncleanPostgresRestart";

function run(command, args, { cwd = repoRoot, env = process.env, input } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, stdio: ["pipe", "pipe", "pipe"] });
    let output = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => { output += chunk; });
    child.stderr.setEncoding("utf8").on("data", (chunk) => { output += chunk; });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) resolve(output.trim());
      else reject(new Error(`${command} ${args.slice(0, 3).join(" ")} failed (${code}): ${output.slice(-3000)}`));
    });
    child.stdin.end(input);
  });
}

async function waitForPostgres() {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      await run("docker", ["exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "reef", "-d", "reef"]);
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
  throw new Error("disposable PostgreSQL did not become ready");
}

async function applySql(file) {
  const sql = await readFile(file, "utf8");
  await run("docker", ["exec", "-i", container, "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef"], { input: sql });
}

async function applyMigrations() {
  // Exercise the versioned SQL schema directly on a disposable database;
  // the Gradle test then starts runtime persistence in Validate mode. The
  // migration runner's checksum/order behavior has its own focused tests.
  await applySql(path.join(repoRoot, "scripts/dev/db/init/001_create_domain_schemas.sql"));
  for (const domain of migrationDomains) {
    const directory = path.join(repoRoot, "scripts/dev/db/migrations", domain);
    const files = (await readdir(directory)).filter((name) => /^\d{4}_.+\.sql$/.test(name)).sort();
    for (const file of files) await applySql(path.join(directory, file));
    console.log(`applied ${files.length} ${domain} migrations`);
  }
}

async function runGradle(port) {
  const env = {
    ...process.env,
    REEF_F02_CRASH_TEST_CONTAINER: container,
    REEF_F02_CRASH_TEST_PORT: port,
    RUNTIME_POSTGRES_JDBC_URL_TEST: `jdbc:postgresql://127.0.0.1:${port}/reef`,
    RUNTIME_POSTGRES_USER_TEST: "reef",
    RUNTIME_POSTGRES_PASSWORD_TEST: "reef",
  };
  await new Promise((resolve, reject) => {
    const child = spawn("./gradlew", ["test", "--tests", testName, "--console=plain"], {
      cwd: path.join(repoRoot, "services/platform-runtime"),
      env,
      stdio: "inherit",
    });
    child.on("error", reject);
    child.on("close", (code) => code === 0 ? resolve() : reject(new Error(`crash regression failed (${code})`)));
  });
}

async function main() {
  if (!/^\d+$/.test(port) || Number(port) < 20000 || Number(port) > 65535) {
    throw new Error("F02 crash-test port must be between 20000 and 65535");
  }
  let created = false;
  try {
    await run("docker", ["run", "-d", "--name", container, "-p", `127.0.0.1:${port}:5432`, "-e", "POSTGRES_USER=reef", "-e", "POSTGRES_PASSWORD=reef", "-e", "POSTGRES_DB=reef", "postgres:16-alpine"]);
    created = true;
    await waitForPostgres();
    await applyMigrations();
    await runGradle(port);
  } finally {
    if (created) await run("docker", ["rm", "-f", "-v", container]);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
