import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { composeArgs } from "./compose-utils.mjs";

const exec = promisify(execFile);
const SCHEMA = "reef.benchmarkRuntimeConfiguration.v1";
const TOGGLE = "PROJECTION_DOWNSTREAM_INSTRUMENTATION_ENABLED";
const DATABASES = ["postgres", "boundary-postgres", "projection-postgres"];
const ROLES = {
  api: /^platform-api$/,
  engine: /^matching-engine$/,
  projector: /^platform-projector-\d+$/,
  materializer: /^platform-materializer(?:-\d+)?$/,
  broker: /^(nats|redpanda)$/,
  ...Object.fromEntries(DATABASES.map((name) => [name, new RegExp(`^${name}$`)])),
};
const SQL = "SELECT json_agg(s ORDER BY name) FROM (SELECT name, setting, unit, pending_restart FROM pg_settings) s;";

// The runner must return stdout only, never log it: inspect/pg_settings can contain secrets.
export async function collectBenchmarkRuntimeSnapshot({ run = defaultRun, processEnv = process.env } = {}) {
  try {
    const ids = String(await run("docker", composeArgs(["ps", "--status", "running", "-q"], processEnv))).trim().split(/\s+/).filter(Boolean);
    if (!ids.length) throw Error("missing services");
    const containers = JSON.parse(await run("docker", ["inspect", ...ids]));
    if (!Array.isArray(containers) || containers.length !== ids.length) throw Error("incomplete inspect");
    const info = JSON.parse(await run("docker", ["info", "--format", "{{json .}}"]));
    if (!(info.NCPU > 0 && info.MemTotal > 0 && info.Architecture && info.KernelVersion && info.ServerVersion)) throw Error("missing host config");
    const services = [];
    for (const container of containers) {
      const config = container.Config;
      const service = config?.Labels?.["com.docker.compose.service"];
      if (!service || !container.State?.Running || !/^sha256:[a-f0-9]{64}$/.test(container.Image) ||
          !Array.isArray(config.Env) || !container.HostConfig || !Array.isArray(container.Mounts)) throw Error("missing service config");
      const environment = config.Env.filter((entry) => entry.split("=", 1)[0] !== TOGGLE).sort();
      let databaseSettingsHash = null;
      if (DATABASES.includes(service)) {
        const env = Object.fromEntries(config.Env.map((entry) => { const index = entry.indexOf("="); return [entry.slice(0, index), entry.slice(index + 1)]; }));
        const settings = JSON.parse(await run("docker", ["exec", container.Id, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", env.POSTGRES_USER || "postgres", "-d", env.POSTGRES_DB || env.POSTGRES_USER || "postgres", "-At", "-c", SQL]));
        if (!Array.isArray(settings) || !settings.length || !settings.every((row) => typeof row.name === "string" && typeof row.setting === "string")) throw Error("missing database config");
        databaseSettingsHash = hash(settings.sort((a, b) => a.name.localeCompare(b.name)));
      }
      services.push({
        service,
        imageId: container.Image,
        environmentHash: hash(environment),
        commandHash: hash({ entrypoint: config.Entrypoint, command: config.Cmd, user: config.User, workingDir: config.WorkingDir }),
        resourcesHash: hash(normalizeHostConfig(container.HostConfig)),
        // Docker may reorder Mounts between inspections; retain every mount field.
        mountsHash: hash(container.Mounts.map((mount) => JSON.stringify(canonical(mount))).sort()),
        databaseSettingsHash,
      });
    }
    services.sort((a, b) => a.service.localeCompare(b.service) || a.environmentHash.localeCompare(b.environmentHash));
    if (!hasRoles(services)) throw Error("missing required roles");
    const hostHash = hash({ cpuCount: info.NCPU, memoryBytes: info.MemTotal, architecture: info.Architecture, os: info.OSType, kernel: info.KernelVersion, operatingSystem: info.OperatingSystem, dockerVersion: info.ServerVersion });
    return { schemaVersion: SCHEMA, complete: true, services, hostHash, fingerprint: hash({ services, hostHash }) };
  } catch {
    // Never surface command errors: stderr/arguments may contain credentials.
    return { schemaVersion: SCHEMA, complete: false, fingerprint: null, reason: "runtime-configuration-collection-incomplete" };
  }
}

export function buildRuntimeConfigurationEvidence(before, after) {
  return { schemaVersion: SCHEMA, before, after, complete: validSnapshot(before) && validSnapshot(after) && before.fingerprint === after.fingerprint };
}

export function runtimeConfigurationSignature(evidence) {
  if (evidence?.schemaVersion !== SCHEMA || evidence.complete !== true || !validSnapshot(evidence.before) || !validSnapshot(evidence.after) || evidence.before.fingerprint !== evidence.after.fingerprint) return null;
  return evidence.before.fingerprint;
}

function normalizeHostConfig(config) {
  if (!config || typeof config !== "object" || Array.isArray(config)) throw Error("invalid host config");
  // Binds order is not semantic. Keep absent/null/[] distinct and preserve every
  // bind string verbatim, including host path spellings, options, and duplicates.
  if (!Object.hasOwn(config, "Binds") || config.Binds === null) return config;
  if (!Array.isArray(config.Binds) || !config.Binds.every((bind) => typeof bind === "string")) throw Error("invalid binds");
  return { ...config, Binds: [...config.Binds].sort() };
}

function validSnapshot(value) {
  return value?.schemaVersion === SCHEMA && value.complete === true && digest(value.hostHash) &&
    Array.isArray(value.services) && hasRoles(value.services) && value.services.every((service) =>
      /^sha256:[a-f0-9]{64}$/.test(service.imageId) &&
      [service.environmentHash, service.commandHash, service.resourcesHash, service.mountsHash].every(digest) &&
      (!DATABASES.includes(service.service) || digest(service.databaseSettingsHash))) &&
    value.fingerprint === hash({ services: value.services, hostHash: value.hostHash });
}
function hasRoles(services) { return services.every((value) => value && typeof value.service === "string") && Object.values(ROLES).every((pattern) => services.some((value) => pattern.test(value.service))); }
function digest(value) { return typeof value === "string" && /^[a-f0-9]{64}$/.test(value); }
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  return value;
}
function hash(value) { return createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex"); }
async function defaultRun(command, args) { return (await exec(command, args, { timeout: 30_000, maxBuffer: 16 * 1024 * 1024 })).stdout; }
