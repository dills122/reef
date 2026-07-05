import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { env, loadDotEnv } from "../lib/dev-utils.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../../..");
const defaultMigrationsRoot = path.join(repoRoot, "scripts/dev/db/migrations");
const domainOrder = ["runtime", "auth", "admin", "boundary", "command_log", "orchestration", "arena", "analytics"];

export async function discoverMigrations(migrationsRoot = defaultMigrationsRoot) {
  const migrations = [];
  for (const domain of domainOrder) {
    const domainDir = path.join(migrationsRoot, domain);
    let entries;
    try {
      entries = await readdir(domainDir, { withFileTypes: true });
    } catch (error) {
      if (error?.code === "ENOENT") continue;
      throw error;
    }
    const files = entries
      .filter((entry) => entry.isFile() && /^\d{4}_.+\.sql$/.test(entry.name))
      .map((entry) => entry.name)
      .sort();

    for (const filename of files) {
      const filePath = path.join(domainDir, filename);
      const sql = await readFile(filePath, "utf8");
      migrations.push({
        domain,
        filename,
        id: `${domain}/${filename}`,
        path: filePath,
        checksum: sha256(sql),
        sql,
      });
    }
  }
  return migrations;
}

export function validateMigrationOrder(migrations) {
  const seen = new Set();
  for (const migration of migrations) {
    if (seen.has(migration.id)) {
      throw new Error(`duplicate migration id: ${migration.id}`);
    }
    seen.add(migration.id);
  }

  for (const domain of domainOrder) {
    const domainMigrations = migrations.filter((migration) => migration.domain === domain);
    const filenames = domainMigrations.map((migration) => migration.filename);
    const sorted = [...filenames].sort();
    if (filenames.join("\n") !== sorted.join("\n")) {
      throw new Error(`migration files are not sorted for domain ${domain}`);
    }
  }
}

export function buildApplySql(migration) {
  return `
BEGIN;
${migration.sql.trim()}

INSERT INTO public.reef_schema_migrations(migration_id, domain_name, filename, checksum_sha256)
VALUES (${sqlString(migration.id)}, ${sqlString(migration.domain)}, ${sqlString(migration.filename)}, ${sqlString(migration.checksum)});
COMMIT;
`.trim();
}

export function migrationsForTarget(target, migrations) {
  if (!target.domains) return migrations;
  const domains = new Set(target.domains);
  return migrations.filter((migration) => domains.has(migration.domain));
}

async function main() {
  loadDotEnv();
  const dryRun = process.argv.includes("--dry-run");
  const migrations = await discoverMigrations();
  validateMigrationOrder(migrations);

  if (dryRun) {
    for (const migration of migrations) {
      console.log(`${migration.id} ${migration.checksum}`);
    }
    console.log(`validated ${migrations.length} migrations`);
    return;
  }

  for (const target of migrationTargets()) {
    await applyMigrationsToTarget(target, migrations);
  }
}

async function applyMigrationsToTarget(target, migrations) {
  const targetMigrations = migrationsForTarget(target, migrations);
  if (target.label !== "primary") {
    console.log(`migrating ${target.label} database (${target.service})`);
  }
  await runPsql(
    `
CREATE TABLE IF NOT EXISTS public.reef_schema_migrations (
  migration_id TEXT PRIMARY KEY,
  domain_name TEXT NOT NULL,
  filename TEXT NOT NULL,
  checksum_sha256 TEXT NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
`,
    { target },
  );

  for (const migration of targetMigrations) {
    const displayId = target.label === "primary" ? migration.id : `${target.label}:${migration.id}`;
    const existingChecksum = (
      await runPsql(
        `SELECT COALESCE((SELECT checksum_sha256 FROM public.reef_schema_migrations WHERE migration_id = ${sqlString(migration.id)}), '');`,
        { capture: true, target },
      )
    ).trim();

    if (existingChecksum) {
      if (existingChecksum !== migration.checksum) {
        throw new Error(
          `checksum mismatch for ${displayId}: applied=${existingChecksum} current=${migration.checksum}`,
        );
      }
      console.log(`skip ${displayId}`);
      continue;
    }

    console.log(`apply ${displayId}`);
    await runPsql(buildApplySql(migration), { target });
  }
}

function migrationTargets() {
  const targets = [
    {
      label: "primary",
      service: env("REEF_POSTGRES_SERVICE", "postgres"),
      user: env("REEF_POSTGRES_USER", "reef"),
      dbName: env("REEF_POSTGRES_DB", "reef"),
      domains: ["runtime", "auth", "admin", "boundary", "command_log", "orchestration", "analytics"],
    },
  ];
  if (env("REEF_PROJECTION_POSTGRES_MIGRATIONS", "1") !== "0") {
    targets.push({
      label: "projection",
      service: env("REEF_PROJECTION_POSTGRES_SERVICE", "projection-postgres"),
      user: env("REEF_PROJECTION_POSTGRES_USER", env("REEF_POSTGRES_USER", "reef")),
      dbName: env("REEF_PROJECTION_POSTGRES_DB", env("REEF_POSTGRES_DB", "reef")),
      domains: ["runtime", "auth", "admin", "boundary", "command_log", "orchestration", "analytics"],
    });
  }
  if (env("REEF_BOUNDARY_POSTGRES_MIGRATIONS", "1") !== "0") {
    targets.push({
      label: "boundary",
      service: env("REEF_BOUNDARY_POSTGRES_SERVICE", "boundary-postgres"),
      user: env("REEF_BOUNDARY_POSTGRES_USER", env("REEF_POSTGRES_USER", "reef")),
      dbName: env("REEF_BOUNDARY_POSTGRES_DB", env("REEF_POSTGRES_DB", "reef")),
      domains: ["runtime", "auth", "admin", "boundary", "command_log", "orchestration", "analytics"],
    });
  }
  if (env("REEF_ARENA_POSTGRES_MIGRATIONS", "1") !== "0") {
    targets.push({
      label: "arena",
      service: env("REEF_ARENA_POSTGRES_SERVICE", "arena-postgres"),
      user: env("REEF_ARENA_POSTGRES_USER", env("REEF_POSTGRES_USER", "reef")),
      dbName: env("REEF_ARENA_POSTGRES_DB", env("REEF_POSTGRES_DB", "reef")),
      domains: ["arena"],
    });
  }
  return targets;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function runPsql(sql, options = {}) {
  const { capture = false, target = migrationTargets()[0] } = options;
  const args = [
    "compose",
    "-f",
    "docker-compose.yml",
    "exec",
    "-T",
    target.service,
    "psql",
    "-U",
    target.user,
    "-d",
    target.dbName,
    "-v",
    "ON_ERROR_STOP=1",
    "-X",
    "-q",
    "-t",
    "-A",
  ];

  return new Promise((resolve, reject) => {
    const child = spawn("docker", args, {
      cwd: repoRoot,
      stdio: ["pipe", capture ? "pipe" : "inherit", capture ? "pipe" : "inherit"],
      env: process.env,
    });
    let stdout = "";
    let stderr = "";
    if (capture) {
      child.stdout.on("data", (chunk) => {
        stdout += chunk;
      });
      child.stderr.on("data", (chunk) => {
        stderr += chunk;
      });
    }
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve(capture ? stdout : "");
        return;
      }
      const tail = capture && stderr ? `: ${stderr.trim()}` : "";
      reject(new Error(`docker ${args.join(" ")} failed with code ${code}${tail}`));
    });
    child.stdin.end(sql);
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error?.message ?? error);
    process.exit(1);
  });
}
