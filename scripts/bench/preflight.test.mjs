import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import * as checks from "./lib/checks.mjs";
import { composeDefaults, parseEnvFile, resolveEnv } from "./lib/env.mjs";
import { loadProfile, parseProfile } from "./lib/profile.mjs";
import { PROFILE_FILES, buildStamp, renderTable, runChecks } from "./preflight.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../..");
const read = (path) => readFileSync(join(root, path), "utf8");
const profiles = Object.fromEntries(Object.entries(PROFILE_FILES).map(([cls, file]) => [cls, loadProfile(join(here, "profiles", file))]));
const composeDef = composeDefaults(read("compose.base.yml"));
const byId = (results, id) => results.find((r) => r.id === id);
const envFromProfile = (profile) => Object.fromEntries(profile.entries.filter((e) => e.family === "env").map((e) => [e.key, { value: e.value, source: "fixture" }]));

const goodDocker = { available: true, ncpu: 10, memBytes: 15.6 * 1024 ** 3, serverVersion: "29.0", containers: [] };
const goodGit = { available: true, sha: "abc123", branch: "master", dirtyTracked: [] };
const laptop = { platform: "darwin", arch: "arm64", cpus: 10, cpuModel: "Apple M1 Max", memGiB: 32 };
const droplet = { platform: "linux", arch: "x64", cpus: 16, cpuModel: "Intel(R) Xeon(R) Platinum 8168", memGiB: 32 };
const goodFacts = { git: goodGit, host: laptop, docker: goodDocker, topics: null, tools: { go: "1.27.2", goRequired: "1.26.9", java: "21.0.5" } };

// ---- profiles --------------------------------------------------------------------------------------
test("all three profiles parse and every key documents why, default and enforce", () => {
  for (const [cls, profile] of Object.entries(profiles)) {
    assert.equal(profile.header.class, cls);
    assert.ok(profile.entries.length > 5, cls);
    assert.ok(profile.header.commands.length >= 1);
    assert.ok(profile.conflicts.length >= 3, `${cls} must record its CONFLICTS`);
    for (const entry of profile.entries) assert.ok(entry.why && entry.default && entry.enforce, entry.key);
  }
});

test("parser rejects a key without a why comment and bad enforce values", () => {
  const head = "# @profile: x\n# @class: a\n# @purpose: p\n# @command: c\n# @pass: p\n\n";
  assert.throws(() => parseProfile(`${head}# default: 1\n# enforce: required\nK=1\n`), /missing a '# why:'/);
  assert.throws(() => parseProfile(`${head}# why: w\n# default: 1\n# enforce: maybe\nK=1\n`), /bad enforce/);
  assert.throws(() => parseProfile(`${head}# why: w\n# default: 1\n# enforce: required\nK=1\n\n# why: w\n# default: 1\n# enforce: required\nK=2\n`), /duplicate key/);
});

test("class A profile values equal the CI script defaults (thresholds untouched)", () => {
  const matching = read("scripts/ci/check-matching-bench.sh");
  const runtime = read("scripts/ci/check-runtime-bench.sh");
  for (const entry of profiles.a.entries.filter((e) => /^(BENCH_REGEX|LIMIT_|REEF_RUNTIME_)/.test(e.key))) {
    const pattern = new RegExp(`\\$\\{${entry.key}:-([^}]*)\\}`);
    const found = pattern.exec(matching)?.[1] ?? pattern.exec(runtime)?.[1];
    assert.equal(found, entry.value, entry.key);
  }
});

test("class C launcher annotations match the launcher scripts", () => {
  const sources = { materializer: read("scripts/dev/venue-event-materializer-stress.mjs"), nodb: read("scripts/dev/lib/dev-stack-profiles.mjs") };
  const call = (source, fn, key, value) => new RegExp(`${fn}\\("${key}",\\s*"${value.replace(/\./g, "\\.")}"\\)`).test(source);
  for (const entry of profiles.c.entries.filter((e) => e.family === "env")) {
    for (const launcher of entry.forcedBy) assert.ok(call(sources[launcher], "setValue", entry.key, entry.value), `${launcher} forces ${entry.key}`);
    for (const launcher of entry.defaultedBy) assert.ok(call(sources[launcher], "setDefault", entry.key, entry.value), `${launcher} defaults ${entry.key}`);
  }
});

test("class B profile matches the Calcify bootstrap and has no unresolved overlay keys", () => {
  const { overlay } = checks.extractBootstrapOverlay(read("scripts/dev/calcify-direct-bootstrap.mjs"), {});
  const results = checks.checkOverlay(profiles.b.entries, overlay, "bootstrap");
  assert.ok(results.length >= 20);
  assert.deepEqual(results.filter((r) => r.status !== checks.PASS).map((r) => `${r.id} ${r.actual}`), []);
});

test("compose default claims in the C and B profiles hold on compose.base.yml", () => {
  assert.equal(composeDef.PLATFORM_HTTP_SERVER, "jdk");
  assert.equal(composeDef.STREAM_ACK_LOG_PROVIDER, "jetstream");
  assert.equal(composeDef.STREAM_ACK_INTAKE_STORE, "postgres");
  assert.equal(composeDef.EXTERNAL_API_IDEMPOTENCY_STORE, "postgres");
  assert.equal(composeDef.STREAM_ACK_PARTITION_COUNT, "64");
  assert.equal(composeDef.STREAM_ACK_PUBLISH_PIPELINE_ENABLED, "false");
  assert.equal(composeDef.MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES, "1048576");
});

// ---- env resolution ----------------------------------------------------------------------------------------------
test("env resolution: process wins over files, first file wins, empty counts as unset", () => {
  const resolved = resolveEnv({ A: "p", B: "" }, [{ path: ".env", text: "A=file\nB=b1\nC='c1'\n#X=1" }, { path: ".env.local", text: "C=c2\nD=d" }]);
  assert.deepEqual(Object.fromEntries(Object.entries(resolved).map(([k, v]) => [k, `${v.value}@${v.source}`])), { A: "p@process env", B: "b1@.env", C: "c1@.env", D: "d@.env.local" });
  assert.deepEqual(parseEnvFile('X="a b"\n\nbad'), { X: "a b" });
});

// ---- class C traps -----------------------------------------------------------------------------------------------------------
const evalC = (overrides, launcher = "none") => {
  const resolved = { ...envFromProfile(profiles.c), ...overrides };
  for (const [k, v] of Object.entries(overrides)) if (v === null) delete resolved[k];
  return runChecks({ cls: "c", profile: profiles.c, resolved, facts: { ...goodFacts, host: droplet }, launcher, target: "local", entry: "soak", processEnv: {}, composeDef });
};

test("class C good config passes with no FAIL", () => {
  const results = evalC({});
  assert.deepEqual(results.filter((r) => r.status === checks.FAIL), []);
  assert.equal(checks.verdict(results).exitCode, 0);
});

for (const [name, key, bad] of [
  ["wrong HTTP server (jdk)", "PLATFORM_HTTP_SERVER", "jdk"],
  ["postgres intake store", "STREAM_ACK_INTAKE_STORE", "postgres"],
  ["postgres idempotency store", "EXTERNAL_API_IDEMPOTENCY_STORE", "postgres"],
  ["jetstream log provider", "STREAM_ACK_LOG_PROVIDER", "jetstream"],
  ["64 partitions", "STREAM_ACK_PARTITION_COUNT", "64"],
  ["publish pipeline missing", "STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "false"],
  ["unbounded intake", "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES", "0"],
  ["unbounded terminal retention", "MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", "0"],
]) {
  test(`class C trap fails: ${name}`, () => {
    const results = evalC({ [key]: { value: bad, source: "fixture" } });
    assert.equal(byId(results, `env:${key}`).status, checks.FAIL);
    assert.equal(checks.verdict(results).exitCode, 1);
  });
}

test("class C: raw compose defaults (nothing set, no launcher) trip every hot-path trap", () => {
  const results = runChecks({ cls: "c", profile: profiles.c, resolved: {}, facts: { ...goodFacts, host: droplet }, launcher: "none", target: "local", entry: "soak", processEnv: {}, composeDef });
  const failed = results.filter((r) => r.status === checks.FAIL).map((r) => r.id);
  for (const id of ["env:PLATFORM_HTTP_SERVER", "env:STREAM_ACK_INTAKE_STORE", "env:EXTERNAL_API_IDEMPOTENCY_STORE", "env:STREAM_ACK_LOG_PROVIDER",
    "env:STREAM_ACK_PARTITION_COUNT", "env:STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "env:MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES"]) assert.ok(failed.includes(id), id);
});

test("class C: STREAM_ACK_LOG_PROVIDER is forced to redpanda by the nodb launcher", () => {
  const results = evalC({ STREAM_ACK_LOG_PROVIDER: null }, "nodb");
  assert.equal(byId(results, "env:STREAM_ACK_LOG_PROVIDER").status, checks.PASS);
  assert.equal(byId(evalC({ STREAM_ACK_LOG_PROVIDER: null }, "materializer"), "env:STREAM_ACK_LOG_PROVIDER").status, checks.PASS);
});

test("class C: a launcher-forced key with a wrong .env value only warns", () => {
  const r = byId(evalC({ PLATFORM_HTTP_SERVER: { value: "jdk", source: ".env" } }, "materializer"), "env:PLATFORM_HTTP_SERVER");
  assert.equal(r.status, checks.WARN);
});

test("message cap: 1 MiB without batch<=500+codec is a FAIL, 4 MiB passes, lz4 at batch 500 passes", () => {
  const r = (env, launcher = "none") => checks.checkMessageCap(env, { MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES: "1048576", MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE: "500" }, launcher, { compressionByLauncher: { nodb: "none" } })[0].status;
  assert.equal(r({ MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE: { value: "none" } }), checks.FAIL);
  assert.equal(r({}, "nodb"), checks.FAIL);
  assert.equal(r({ MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE: { value: "1000" } }), checks.FAIL);
  assert.equal(r({}), checks.PASS);
  assert.equal(r({ MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES: { value: "4194304" }, MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE: { value: "none" } }), checks.PASS);
});

// ---- class A thresholds ----------------------------------------------------------------------------------------------------------
test("class A: loosened thresholds FAIL, tighter warn, unset passes", () => {
  const entry = profiles.a.entries.find((e) => e.key === "LIMIT_SUBMIT_NS");
  const status = (value) => checks.checkEnvEntry(entry, value ? { LIMIT_SUBMIT_NS: { value, source: "x" } } : {}, {}, "none").status;
  assert.equal(status("999999"), checks.FAIL);
  assert.equal(status("4000"), checks.WARN);
  assert.equal(status("5000"), checks.PASS);
  assert.equal(status(null), checks.PASS);
  const iter = profiles.a.entries.find((e) => e.key === "REEF_RUNTIME_PERF_ITERATIONS");
  assert.equal(checks.checkEnvEntry(iter, { REEF_RUNTIME_PERF_ITERATIONS: { value: "10", source: "x" } }, {}, "none").status, checks.FAIL);
});

test("class A tools: missing or old toolchain fails, newer JDK warns", () => {
  assert.equal(checks.checkTools({ go: null, java: "21.0.1", goRequired: "1.26.9" })[0].status, checks.FAIL);
  assert.equal(checks.checkTools({ go: "1.25.0", java: "21", goRequired: "1.26.9" })[0].status, checks.FAIL);
  assert.equal(checks.checkTools({ go: "1.26.9", java: "17.0.1", goRequired: "1.26.9" })[1].status, checks.FAIL);
  assert.equal(checks.checkTools({ go: "1.27.2", java: "25.0.1", goRequired: "1.26.9" })[1].status, checks.WARN);
  assert.equal(checks.checkTools({ go: "1.26.9", java: "21.0.5", goRequired: "1.26.9" })[1].status, checks.PASS);
});

// ---- class B ------------------------------------------------------------------------------------------------------------------------------
test("class B: bad overlay values fail (jdk, postgres intake, 1 MiB cap, unpublished pipeline)", () => {
  const { overlay } = checks.extractBootstrapOverlay(read("scripts/dev/calcify-direct-bootstrap.mjs"), {});
  const bad = structuredClone(overlay);
  Object.assign(bad.services["platform-api"].environment, { PLATFORM_HTTP_SERVER: "jdk", STREAM_ACK_INTAKE_STORE: "postgres", STREAM_ACK_PUBLISH_PIPELINE_ENABLED: "false", STREAM_ACK_PARTITION_COUNT: "4" });
  bad.services["matching-engine"].environment.MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES = "1048576";
  const failed = checks.checkOverlay(profiles.b.entries, bad, "x").filter((r) => r.status === checks.FAIL).map((r) => r.id);
  for (const id of ["PLATFORM_HTTP_SERVER", "STREAM_ACK_INTAKE_STORE", "STREAM_ACK_PUBLISH_PIPELINE_ENABLED", "STREAM_ACK_PARTITION_COUNT"]) assert.ok(failed.includes(`overlay:platform-api.${id}`), id);
  assert.ok(failed.includes("overlay:matching-engine.MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES"));
});

test("class B: env overrides of CALCIFY_DIRECT_* flow into the static overlay", () => {
  const { overlay } = checks.extractBootstrapOverlay(read("scripts/dev/calcify-direct-bootstrap.mjs"), { CALCIFY_DIRECT_RESOLVER_THREADS: "1", CALCIFY_DIRECT_ENGINE_BATCH: "1000" });
  assert.equal(overlay.services["calcify-resolver"].environment.CALCIFY_RESOLVER_THREADS, "1");
  assert.equal(overlay.services["matching-engine"].environment.MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE, "1000");
});

test("stale Calcify image tag and bootstrap-without-build fail; plain soak passes; FC_SKIP_BUILD warns", () => {
  assert.equal(checks.checkImages({ CALCIFY_DIRECT_IMAGE: "reef-platform-runtime:calcify-10k-master461" })[0].status, checks.FAIL);
  const direct = checks.checkImages({}, { entry: "bootstrap", defaultImages: {} });
  assert.equal(direct[0].status, checks.FAIL);
  assert.equal(checks.checkImages({}, { entry: "soak" })[0].status, checks.PASS);
  assert.equal(checks.checkImages({ FC_SKIP_BUILD: "true" })[0].status, checks.WARN);
  // bootstrap carries no default tags or project: it must refuse to run without explicit values
  assert.deepEqual(checks.extractBootstrapOverlay(read("scripts/dev/calcify-direct-bootstrap.mjs")).defaultImages, { runtime: null, matching: null });
});

test("class B env knobs: two-step mode, evict off, tmpfs, skip-build and quantity are caught", () => {
  const check = (key, value) => checks.checkEnvEntry(profiles.b.entries.find((e) => e.key === key), { [key]: { value, source: "x" } }, {}, "none").status;
  assert.equal(check("FC_MODE", "two-step"), checks.FAIL);
  assert.equal(check("FC_EVICT", "false"), checks.FAIL);
  assert.equal(check("FC_TMPFS", "true"), checks.FAIL);
  assert.equal(check("FC_SKIP_BUILD", "true"), checks.FAIL);
  assert.equal(check("FC_QUANTITY", "100"), checks.FAIL);
  assert.equal(check("FC_WORKERS", "256"), checks.WARN);
});

// ---- host / docker / git / broker -------------------------------------------------------------------------------------------------------
test("Docker VM sizing", () => {
  assert.equal(checks.checkDockerVm(goodDocker)[0].status, checks.PASS);
  assert.equal(checks.checkDockerVm({ ...goodDocker, ncpu: 4 })[0].status, checks.FAIL);
  assert.equal(checks.checkDockerVm({ ...goodDocker, memBytes: 7.8 * 1024 ** 3 })[0].status, checks.FAIL);
});

test("no Docker daemon is an explicit blocking SKIPPED, never a silent pass (exit 2)", () => {
  const down = { available: false, reason: "Cannot connect", containers: [] };
  const results = [...checks.checkDockerVm(down), ...checks.checkRunningStacks(down, [])];
  assert.ok(results.every((r) => r.status === checks.SKIPPED && r.blocking));
  assert.deepEqual(checks.verdict(results), { status: "COULD_NOT_CHECK", exitCode: 2 });
});

test("running Reef stacks fail unless explicitly allowed; non-Reef containers warn", () => {
  const stacks = [{ name: "reef-platform-api", ports: "0.0.0.0:8080->8080/tcp" }, { name: "postgres-other", ports: "" }];
  const r = checks.checkRunningStacks(goodDocker, stacks);
  assert.equal(r[0].status, checks.FAIL);
  assert.equal(r[1].status, checks.WARN);
  assert.equal(checks.checkRunningStacks(goodDocker, stacks, { allow: true })[0].status, checks.WARN);
  assert.equal(checks.checkRunningStacks(goodDocker, [])[0].status, checks.PASS);
});

test("dirty tracked tree fails droplet runs and only warns locally", () => {
  const dirty = { available: true, sha: "x", branch: "b", dirtyTracked: [" M a.go"] };
  assert.equal(checks.checkGit(dirty, { target: "droplet" })[0].status, checks.FAIL);
  assert.equal(checks.checkGit(dirty, { target: "local" })[0].status, checks.WARN);
  assert.equal(checks.checkGit(goodGit, { target: "droplet" })[0].status, checks.PASS);
  assert.equal(checks.checkGit({ available: false }, {})[0].status, checks.SKIPPED);
});

test("class C on a laptop is diagnostic only; a 16 vCPU Linux host matches the c-16 shape", () => {
  const laptopResult = checks.checkHostClass(laptop, "c")[0];
  assert.equal(laptopResult.status, checks.WARN);
  assert.match(laptopResult.why, /DIAGNOSTIC ONLY, DO NOT QUOTE/);
  assert.equal(laptopResult.diagnosticOnly, true);
  assert.equal(checks.checkHostClass(droplet, "c")[0].status, checks.PASS);
  assert.equal(checks.checkHostClass(droplet, "b")[0].status, checks.WARN);
  assert.equal(checks.checkHostClass({ ...droplet, cpuModel: "AMD EPYC 9555P" }, "b")[0].status, checks.PASS);
});

test("Redpanda topic checks: no broker is SKIPPED, bad write.caching and 1 MiB cap fail, good passes", () => {
  assert.equal(checks.checkTopics(profiles.b.entries, null)[0].status, checks.SKIPPED);
  assert.equal(checks.checkTopics(profiles.b.entries, [{ name: "unrelated", configs: {} }])[0].status, checks.SKIPPED);
  const good = [{ name: "REEF_X_COMMANDS", configs: { "write.caching": "false" } }, { name: "REEF_X_EVENTS", configs: { "write.caching": "false", "max.message.bytes": "4194304" } }];
  assert.deepEqual(checks.checkTopics(profiles.b.entries, good).map((r) => r.status), ["PASS", "PASS", "PASS"]);
  const bad = [{ name: "REEF_X_EVENTS", configs: { "write.caching": "true", "max.message.bytes": "1048576" } }];
  const results = checks.checkTopics(profiles.b.entries, bad);
  assert.deepEqual(results.map((r) => r.status), ["FAIL", "FAIL"]);
  assert.match(results[1].why, /rpk topic alter-config REEF_X_EVENTS --set max.message.bytes=4194304/);
  // class C only warns about write.caching (correct value for the 10k gate is not determined) but fails the cap
  const c = checks.checkTopics(profiles.c.entries, bad);
  assert.deepEqual(c.map((r) => r.status), ["WARN", "FAIL"]);
});

test("verdict ordering: FAIL beats blocking SKIPPED beats WARN", () => {
  const r = (status, extra = {}) => ({ status, ...extra });
  assert.equal(checks.verdict([r("WARN"), r("SKIPPED")]).exitCode, 0);
  assert.equal(checks.verdict([r("WARN"), r("SKIPPED", { blocking: true })]).exitCode, 2);
  assert.equal(checks.verdict([r("FAIL"), r("SKIPPED", { blocking: true })]).exitCode, 1);
});

test("table lists expected vs actual and why for every non-pass row; stamp carries traceability fields", () => {
  const results = evalC({ PLATFORM_HTTP_SERVER: { value: "jdk", source: "fixture" } });
  const table = renderTable(results, "head");
  assert.match(table, /FAIL\s+PLATFORM_HTTP_SERVER\s+netty\s+jdk/);
  assert.match(table, /why: JDK HttpServer/);
  const stamp = buildStamp({ cls: "c", profile: profiles.c, results, verdictResult: checks.verdict(results), facts: { ...goodFacts, host: laptop }, resolved: {}, launcher: "none", target: "local", factsSource: "live", composeSource: "x", skipped: false });
  assert.equal(stamp.schema, "reef-bench-preflight-v1");
  assert.equal(stamp.status, "FAIL");
  assert.equal(stamp.git.sha, "abc123");
  assert.equal(stamp.claimable, false);
  assert.equal(stamp.resolvedSettings["env:PLATFORM_HTTP_SERVER"].actual, "jdk");
});

// ---- CLI ------------------------------------------------------------------------------------------------------------------------------------
const cli = (args, env = {}) => spawnSync(process.execPath, [join(here, "preflight.mjs"), ...args], { encoding: "utf8", env: { PATH: process.env.PATH, HOME: process.env.HOME, ...env } });
const factsFile = (facts) => {
  const dir = mkdtempSync(join(tmpdir(), "bench-preflight-"));
  const path = join(dir, "facts.json");
  writeFileSync(path, JSON.stringify(facts));
  return { dir, path };
};
const profileEnv = (cls) => Object.fromEntries(profiles[cls].entries.filter((e) => e.family === "env").map((e) => [e.key, e.value]));

test("CLI: class C good config exits 0 and bad config exits 1, stamp written, injected facts are never claimable", () => {
  const { dir, path } = factsFile({ ...goodFacts, host: droplet });
  const good = cli(["--class", "c", "--facts", path, "--json", "--stamp-dir", join(dir, "good")], profileEnv("c"));
  assert.equal(good.status, 0, good.stdout + good.stderr);
  const stamp = JSON.parse(readFileSync(join(dir, "good", "preflight.json"), "utf8"));
  assert.equal(stamp.status, "PASS");
  assert.equal(stamp.claimable, false);
  assert.match(stamp.factsSource, /injected/);
  const bad = cli(["--class", "c", "--facts", path], { ...profileEnv("c"), PLATFORM_HTTP_SERVER: "jdk", STREAM_ACK_INTAKE_STORE: "postgres" });
  assert.equal(bad.status, 1);
  assert.match(bad.stdout, /FAIL\s+PLATFORM_HTTP_SERVER/);
});

test("CLI: class B good exits 0 on fixture facts; stale image tag exits 1; no Docker exits 2", () => {
  const { path } = factsFile({ ...goodFacts, host: droplet });
  assert.equal(cli(["--class", "b", "--facts", path]).status, 0);
  assert.equal(cli(["--class", "b", "--facts", path], { CALCIFY_DIRECT_IMAGE: "reef-platform-runtime:calcify-10k-master461" }).status, 1);
  const down = factsFile({ ...goodFacts, docker: { available: false, reason: "down", containers: [] } });
  assert.equal(cli(["--class", "b", "--facts", down.path]).status, 2);
});

test("CLI: BENCH_PREFLIGHT=skip prints the banner, stamps PREFLIGHT SKIPPED and writes the marker file", () => {
  const dir = mkdtempSync(join(tmpdir(), "bench-skip-"));
  const r = cli(["--class", "c", "--stamp-dir", dir], { BENCH_PREFLIGHT: "skip" });
  assert.equal(r.status, 0);
  assert.match(r.stderr, /PREFLIGHT SKIPPED/);
  const stamp = JSON.parse(readFileSync(join(dir, "preflight.json"), "utf8"));
  assert.equal(stamp.status, "PREFLIGHT SKIPPED");
  assert.equal(stamp.claimable, false);
  assert.ok(existsSync(join(dir, "PREFLIGHT_SKIPPED.txt")));
});

test("CLI: usage errors exit 2", () => {
  assert.equal(cli([]).status, 2);
  assert.equal(cli(["--class", "z"]).status, 2);
});
