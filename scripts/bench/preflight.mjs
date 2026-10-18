#!/usr/bin/env node
// Benchmark configuration preflight. Usage:
//   node scripts/bench/preflight.mjs --class a|b|c [--env-file X]... [--json] [--launcher materializer|nodb|none]
//        [--target local|droplet] [--entry soak|bootstrap] [--overlay compose-profile.json]
//        [--stamp-dir DIR] [--facts facts.json]
// Exit codes: 0 pass, 1 configuration FAIL, 2 could not check. BENCH_PREFLIGHT=skip prints a banner,
// stamps "PREFLIGHT SKIPPED" and exits 0 so the run can proceed while being marked invalid.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { loadProfile } from "./lib/profile.mjs";
import { composeDefaults, resolveEnv } from "./lib/env.mjs";
import * as checks from "./lib/checks.mjs";
import { defaultRun, gatherComposeConfig, gatherDocker, gatherGit, gatherHost, gatherTools, gatherTopics } from "./lib/facts.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../..");
export const PROFILE_FILES = {
  a: "class-a-ci-guardrails.env",
  b: "class-b-calcify-fullchain.env",
  c: "class-c-ingestion-10k.env",
};
export const SKIP_BANNER = [
  "################################################################################",
  "#  PREFLIGHT SKIPPED (BENCH_PREFLIGHT=skip)                                    #",
  "#  This run's configuration was NOT verified. Its numbers are INVALID for any   #",
  "#  comparison or claim. The output is stamped 'PREFLIGHT SKIPPED'.            #",
  "################################################################################",
].join("\n");

// ---- orchestration (exported for tests) -----------------------------------------------------------------
export function runChecks({ cls, profile, resolved, facts, launcher, target, entry, overlayData, processEnv, composeDef, bootstrapSource }) {
  const results = [];
  const envEntries = profile.entries.filter((e) => e.family === "env");
  for (const e of envEntries) results.push(checks.checkEnvEntry(e, resolved, composeDef, launcher));
  if (cls === "c") results.push(...checks.checkMessageCap(resolved, composeDef, launcher, { compressionByLauncher: { nodb: "none" } }));
  if (cls === "a") results.push(...checks.checkTools(facts.tools));
  let effectiveOverlay = null;
  if (cls === "b") {
    let overlay = overlayData;
    let label = "generated overlay";
    let defaultImages = {};
    if (!overlay && bootstrapSource) {
      const extracted = checks.extractBootstrapOverlay(bootstrapSource, processEnv);
      overlay = extracted.overlay;
      defaultImages = extracted.defaultImages;
      label = "scripts/dev/calcify-direct-bootstrap.mjs (static)";
    }
    effectiveOverlay = overlay;
    results.push(...checks.checkOverlay(profile.entries, overlay, label));
    results.push(...checks.checkImages(processEnv, { entry, defaultImages }));
  }
  if (cls === "b" || cls === "c") {
    results.push(...checks.checkEffectiveConfig(profile.entries, facts.compose, { overlay: effectiveOverlay, launcher, processEnv }));
    results.push(...checks.checkDockerVm(facts.docker));
    results.push(...checks.checkRunningStacks(facts.docker, facts.docker?.containers ?? [], { allow: processEnv.BENCH_ALLOW_RUNNING_STACK === "1" }));
    results.push(...checks.checkTopics(profile.entries, facts.topics ?? null));
    results.push(...checks.checkHostClass(facts.host, cls));
  }
  results.push(...checks.checkGit(facts.git, { target }));
  return results;
}

export function renderTable(results, header) {
  const rows = results.map((r) => [r.status, r.title, truncate(r.expected, 40), truncate(r.actual, 40)]);
  const widths = [8, 38, 40, 40].map((w, i) => Math.min(w, Math.max(...rows.map((row) => String(row[i]).length), ["STATUS", "CHECK", "EXPECTED", "ACTUAL"][i].length)));
  const fmt = (row) => row.map((cell, i) => String(cell).padEnd(widths[i])).join("  ");
  const lines = [header, "", fmt(["STATUS", "CHECK", "EXPECTED", "ACTUAL"]), fmt(widths.map((w) => "-".repeat(w)))];
  for (const [i, row] of rows.entries()) {
    lines.push(fmt(row));
    if (results[i].status !== checks.PASS) lines.push(`${" ".repeat(widths[0] + 2)}why: ${results[i].why}`);
  }
  return lines.join("\n");
}
const truncate = (text, n) => (String(text).length > n ? `${String(text).slice(0, n - 1)}…` : String(text));

export function buildStamp({ cls, profile, results, verdictResult, facts, resolved, launcher, target, factsSource, composeSource, skipped }) {
  const settings = {};
  for (const r of results) if (r.id.startsWith("env:") || r.id.startsWith("overlay:") || r.id.startsWith("broker:")) {
    settings[r.id] = { expected: r.expected, actual: r.actual, source: r.source ?? null, status: r.status };
  }
  return {
    schema: "reef-bench-preflight-v1",
    status: skipped ? "PREFLIGHT SKIPPED" : verdictResult.status,
    exitCode: skipped ? 0 : verdictResult.exitCode,
    class: cls,
    profile: profile.header.profile,
    profileFile: PROFILE_FILES[cls],
    launcher,
    target,
    timestamp: new Date().toISOString(),
    git: facts.git?.available ? { sha: facts.git.sha, branch: facts.git.branch, dirtyTrackedFiles: facts.git.dirtyTracked.length } : null,
    host: facts.host,
    docker: facts.docker?.available
      ? { available: true, ncpu: facts.docker.ncpu, memGiB: Number((facts.docker.memBytes / 1024 ** 3).toFixed(1)), serverVersion: facts.docker.serverVersion, operatingSystem: facts.docker.operatingSystem }
      : { available: false, reason: facts.docker?.reason ?? null },
    redpanda: facts.redpanda ?? null,
    diagnosticOnly: results.some((r) => r.diagnosticOnly) || skipped === true,
    factsSource,
    composeSource,
    claimable: !skipped && verdictResult.status === "PASS" && factsSource === "live" && !results.some((r) => r.diagnosticOnly),
    resolvedSettings: settings,
    checks: results.map(({ id, status, title, expected, actual }) => ({ id, status, title, expected, actual })),
  };
}

function writeStamp(dir, stamp) {
  mkdirSync(dir, { recursive: true });
  const path = join(dir, "preflight.json");
  writeFileSync(path, `${JSON.stringify(stamp, null, 2)}\n`);
  if (stamp.status === "PREFLIGHT SKIPPED") writeFileSync(join(dir, "PREFLIGHT_SKIPPED.txt"), `${SKIP_BANNER}\n`);
  return path;
}

// ---- CLI --------------------------------------------------------------------------------------------------------------
async function main() {
  const { values } = parseArgs({
    options: {
      class: { type: "string" },
      "env-file": { type: "string", multiple: true },
      json: { type: "boolean", default: false },
      launcher: { type: "string" },
      target: { type: "string", default: "local" },
      entry: { type: "string", default: "soak" },
      overlay: { type: "string" },
      "stamp-dir": { type: "string" },
      facts: { type: "string" },
      help: { type: "boolean", default: false },
    },
  });
  const cls = values.class;
  if (values.help || !PROFILE_FILES[cls]) {
    console.error("usage: node scripts/bench/preflight.mjs --class a|b|c [--env-file X] [--json] [--launcher materializer|nodb|none] [--target local|droplet] [--entry soak|bootstrap] [--overlay F] [--stamp-dir D]");
    process.exit(values.help ? 0 : 2);
  }
  const launcher = values.launcher ?? "none";
  if (!["materializer", "nodb", "none"].includes(launcher)) throw new Error(`bad --launcher ${launcher}`);
  if (!["local", "droplet"].includes(values.target)) throw new Error(`bad --target ${values.target}`);
  if (!["soak", "bootstrap"].includes(values.entry)) throw new Error(`bad --entry ${values.entry}`);

  const profile = loadProfile(join(here, "profiles", PROFILE_FILES[cls]));
  const stampDir = values["stamp-dir"] ? resolve(values["stamp-dir"]) : null;

  if (process.env.BENCH_PREFLIGHT === "skip") {
    console.error(SKIP_BANNER);
    if (stampDir) {
      const git = gatherGit(defaultRun, root);
      const stamp = buildStamp({ cls, profile, results: [], verdictResult: { status: "PREFLIGHT SKIPPED", exitCode: 0 }, facts: { git, host: gatherHost(), docker: { available: false } }, resolved: {}, launcher, target: values.target, factsSource: "skipped", composeSource: null, skipped: true });
      console.error(`stamp: ${writeStamp(stampDir, stamp)}`);
    }
    process.exit(0);
  }

  const run = defaultRun;
  const injected = values.facts ? JSON.parse(readFileSync(values.facts, "utf8")) : null;
  const envFilePaths = values["env-file"] ?? [".env", ".env.local"];
  const files = envFilePaths.map((p) => resolve(root, p)).filter((p) => existsSync(p)).map((p) => ({ path: p, text: readFileSync(p, "utf8") }));
  for (const requested of values["env-file"] ?? []) if (!existsSync(resolve(root, requested))) throw new Error(`--env-file not found: ${requested}`);
  const processEnv = process.env;
  const resolved = resolveEnv(processEnv, files);

  const docker = injected?.docker ?? gatherDocker(run);
  const facts = {
    git: injected?.git ?? gatherGit(run, root),
    host: injected?.host ?? gatherHost(),
    docker,
    topics: injected ? (injected.topics ?? null) : (docker.available ? gatherTopics(run, docker.containers) : null),
    tools: injected?.tools ?? (cls === "a" ? gatherTools(run, readFileSync(join(root, "services/matching-engine/go.mod"), "utf8")) : null),
  };

  const composeFiles = (processEnv.REEF_COMPOSE_FILES ?? "compose.base.yml,compose.local.yml").split(",").map((s) => s.trim()).filter(Boolean);
  if (cls === "b" && !composeFiles.includes("compose.calcify.yml")) composeFiles.push("compose.calcify.yml");
  let composeDef = composeDefaults(readFileSync(join(root, "compose.base.yml"), "utf8"));
  let composeSource = "compose.base.yml (static parse)";
  if (!injected) {
    // Class B always runs these compose profiles (bootstrap sets them); resolve with them so the Calcify services are in the config.
    const composeEnv = cls === "b" ? { ...processEnv, COMPOSE_PROFILES: processEnv.COMPOSE_PROFILES || "redpanda,calcify-phase1,calcify-phase2" } : processEnv;
    const cfg = gatherComposeConfig(run, composeFiles, root, composeEnv);
    if (cfg.ok && (cls === "b" || cls === "c")) {
      // Defaults-only baseline: clean environment and no .env, so a difference to `cfg` is exactly an override.
      const cleanEnv = Object.fromEntries(["PATH", "HOME", "DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CONTEXT", "COMPOSE_PROFILES"].flatMap((k) => (composeEnv[k] ? [[k, composeEnv[k]]] : [])));
      const base = gatherComposeConfig(run, composeFiles, root, cleanEnv, { defaultsOnly: true });
      if (base.ok) facts.compose = { effective: cfg.services, baseline: base.services };
    }
    if (cfg.ok) {
      const merged = {};
      for (const name of ["platform-api", "matching-engine"]) for (const [k, v] of Object.entries(cfg.services[name]?.environment ?? {})) if (!(k in merged)) merged[k] = v;
      composeDef = { ...composeDef, ...Object.fromEntries(Object.entries(merged).filter(([k]) => k in composeDef)) };
      composeSource = `docker compose config (${composeFiles.join(" + ")})`;
      const rp = cfg.services.redpanda;
      if (rp) facts.redpanda = { image: rp.image, command: (rp.command ?? []).join(" ") };
    } else {
      composeSource = `compose.base.yml (static parse; docker compose config unavailable: ${cfg.reason})`;
    }
  }

  const overlayData = values.overlay ? JSON.parse(readFileSync(resolve(values.overlay), "utf8")) : null;
  const bootstrapSource = cls === "b" ? readFileSync(join(root, "scripts/dev/calcify-direct-bootstrap.mjs"), "utf8") : null;
  const results = runChecks({ cls, profile, resolved, facts, launcher, target: values.target, entry: values.entry, overlayData, processEnv, composeDef, bootstrapSource });
  const verdictResult = checks.verdict(results);
  const factsSource = injected ? "injected (fixture, not a live host)" : "live";
  const stamp = buildStamp({ cls, profile, results, verdictResult, facts, resolved, launcher, target: values.target, factsSource, composeSource, skipped: false });

  if (values.json) console.log(JSON.stringify(stamp, null, 2));
  else {
    const head = `Benchmark preflight  class=${cls}  profile=${profile.header.profile}  launcher=${launcher}  target=${values.target}\ncompose defaults: ${composeSource}\nfacts: ${factsSource}`;
    console.log(renderTable(results, head));
    const nonClaim = stamp.diagnosticOnly ? "  [DIAGNOSTIC ONLY - do not quote these numbers]" : "";
    console.log(`\nRESULT: ${verdictResult.status} (exit ${verdictResult.exitCode})${nonClaim}`);
    if (verdictResult.status !== "PASS") console.log("Escape hatch (numbers get stamped INVALID): BENCH_PREFLIGHT=skip. See scripts/bench/README.md.");
  }
  if (stampDir) {
    const path = writeStamp(stampDir, stamp);
    if (!values.json) console.log(`stamp: ${path}`);
  }
  process.exit(verdictResult.exitCode);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(`preflight error: ${error.message}`);
    process.exit(2);
  });
}
