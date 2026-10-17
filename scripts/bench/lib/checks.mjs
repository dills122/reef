// Pure preflight checks. Each returns an array of results:
//   { id, status: PASS|FAIL|WARN|SKIPPED, title, expected, actual, why, blocking? }
// `blocking` on a SKIPPED result means "could not check something that must be checked" (exit 2).
export const PASS = "PASS";
export const FAIL = "FAIL";
export const WARN = "WARN";
export const SKIPPED = "SKIPPED";

const GIB = 1024 ** 3;
const result = (id, status, title, expected, actual, why, extra = {}) => ({ id, status, title, expected, actual, why, ...extra });
const num = (value) => (value === undefined || value === "" || value === null ? NaN : Number(value));

function severity(entry) {
  return entry.enforce === "advisory" ? WARN : FAIL;
}

// ---- env family ---------------------------------------------------------------------------
// resolved: { KEY: { value, source } }  composeDef: { KEY: default } (class C: the compose defaults)
// launcher: "materializer" | "nodb" | "none" (the script that will run; none = compose / DO host / manual)
export function checkEnvEntry(entry, resolved, composeDef, launcher) {
  const user = resolved[entry.key];
  const id = `env:${entry.key}`;
  const title = entry.key;
  const forced = entry.forcedBy.includes(launcher);
  const defaulted = entry.defaultedBy.includes(launcher);
  const bad = severity(entry);

  if (forced) {
    if (user && user.value !== entry.value) {
      return result(id, WARN, title, entry.value, user.value, `${launcher} launcher overwrites your ${user.source} value with ${entry.value} (${entry.why})`, { source: `launcher ${launcher} (forced)` });
    }
    return result(id, PASS, title, entry.value, entry.value, entry.why, { source: `launcher ${launcher} (forced)` });
  }
  if (user) {
    return judge(entry, id, title, user.value, user.source, bad);
  }
  if (defaulted) {
    return result(id, PASS, title, entry.value, entry.value, entry.why, { source: `launcher ${launcher} (default when unset)` });
  }
  if (entry.key in composeDef) {
    return judge(entry, id, title, composeDef[entry.key], "compose default (unset)", bad);
  }
  if (entry.enforce === "required") {
    return result(id, FAIL, title, entry.value, "(unset)", `${entry.why} Set it explicitly: set -a; . <profile>; set +a`, { source: "unset" });
  }
  return result(id, PASS, title, entry.value, "(unset: script default)", entry.why, { source: "script default" });
}

function judge(entry, id, title, actualRaw, source, bad) {
  const actual = actualRaw === "" ? "(empty)" : actualRaw;
  const compare = entry.compare;
  if (compare) {
    const a = num(actualRaw);
    const e = num(entry.value);
    if (!Number.isFinite(a)) return result(id, bad, title, entry.value, actual, `${entry.why} (not a number)`, { source });
    if (a === e) return result(id, PASS, title, entry.value, actual, entry.why, { source });
    const loosened = compare === "max" ? a > e : a < e;
    if (loosened) {
      return result(id, FAIL, title, `${compare === "max" ? "<=" : ">="} ${entry.value}`, actual, `${entry.why} Loosened versus the CI value: results are not comparable with CI.`, { source });
    }
    return result(id, WARN, title, entry.value, actual, `tighter than CI (${entry.why})`, { source });
  }
  if (String(actual).trim() === entry.value) return result(id, PASS, title, entry.value, actual, entry.why, { source });
  return result(id, bad, title, entry.value, actual, entry.why, { source });
}

// ---- 1 MiB message cap -------------------------------------------------------------------------
// Pairing rule: an engine producer cap below 4 MiB is only safe at batch <= 500 with compression.
export function checkMessageCap(resolved, composeDef, launcher, { compressionByLauncher = {} } = {}) {
  const cap = num(resolved.MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES?.value ?? composeDef.MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES ?? 1048576);
  const batch = num(resolved.MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE?.value ?? composeDef.MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE ?? 500);
  const explicit = resolved.MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE?.value;
  const compression = (explicit ?? compressionByLauncher[launcher] ?? resolved.STREAM_ACK_KAFKA_COMPRESSION_TYPE?.value ?? composeDef.STREAM_ACK_KAFKA_COMPRESSION_TYPE ?? "lz4").toLowerCase();
  const need = 4 * 1024 * 1024;
  const risky = cap < need && (compression === "none" || batch > 500);
  return [result(
    "env:message-cap-pairing",
    risky ? FAIL : PASS,
    "producer message cap vs batch/codec",
    `cap >= ${need} unless batch <= 500 and compressed`,
    `cap=${cap} batch=${batch} compression=${compression}`,
    "A 1 MiB producer cap with an uncompressed or large batch makes the engine nack/fatal (about 2.2 KB per command event, 500 commands ~1.1 MB). Raise MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES=4194304 AND the events topic max.message.bytes.",
  )];
}

// ---- overlay family (Calcify bootstrap compose overlay) -----------------------------------------
// overlay: { services: { name: { environment: {...} } } }
export function checkOverlay(entries, overlay, sourceLabel) {
  const out = [];
  for (const entry of entries.filter((e) => e.family === "overlay")) {
    const [service, ...rest] = entry.key.split(".");
    const name = rest.join(".");
    const actual = overlay?.services?.[service]?.environment?.[name];
    const id = `overlay:${entry.key}`;
    if (actual === undefined) {
      out.push(result(id, SKIPPED, entry.key, entry.value, "(not determinable)", `${entry.why} Not found in ${sourceLabel}.`, { source: sourceLabel }));
    } else if (String(actual) === entry.value) {
      out.push(result(id, PASS, entry.key, entry.value, String(actual), entry.why, { source: sourceLabel }));
    } else {
      out.push(result(id, severity(entry), entry.key, entry.value, String(actual), entry.why, { source: sourceLabel }));
    }
  }
  return out;
}

// Static extraction of the overlay from scripts/dev/calcify-direct-bootstrap.mjs. Returns
// { overlay, defaultImage } where overlay has the same shape as the generated compose-profile.json.
// Values that come from `process.env.X ?? 'default'` are resolved against processEnv.
export function extractBootstrapOverlay(source, processEnv = {}) {
  const services = {};
  const pairs = (text, scope) => {
    const env = {};
    for (const m of text.matchAll(/([A-Z][A-Z0-9_]*):\s*(?:process\.env\.([A-Z0-9_]+)\s*\?\?\s*)?'([^']*)'/g)) {
      const [, key, envVar, dflt] = m;
      env[key] = envVar && processEnv[envVar] ? processEnv[envVar] : dflt;
    }
    if (scope) services[scope] = { environment: { ...(services[scope]?.environment ?? {}), ...env } };
    return env;
  };
  const lineOf = (name) => source.split("\n").find((line) => line.startsWith(`const ${name}=`)) ?? "";
  pairs(lineOf("ingress"), "platform-api");
  pairs(lineOf("engine"), "matching-engine");
  const common = pairs(lineOf("common"), null);
  const overlayLine = lineOf("overlay");
  const segment = (name) => {
    const start = overlayLine.indexOf(`'${name}':`);
    if (start < 0) return "";
    const rest = overlayLine.slice(start + name.length + 3);
    const next = rest.search(/'calcify-[a-z]+':|'matching-engine':/);
    return next < 0 ? rest : rest.slice(0, next);
  };
  for (const name of ["calcify-extractor", "calcify-verifier", "calcify-resolver"]) {
    const seg = segment(name);
    pairs(seg, name);
    services[name].environment = { ...common, ...services[name].environment };
  }
  const image = /const image=process\.env\.CALCIFY_DIRECT_IMAGE\s*\?\?\s*'([^']*)'/.exec(source)?.[1] ?? null;
  const matcher = /CALCIFY_DIRECT_MATCHING_IMAGE\s*\?\?\s*'([^']*)'/.exec(source)?.[1] ?? null;
  return { overlay: { services }, defaultImages: { runtime: image, matching: matcher } };
}

// ---- Calcify image tags ---------------------------------------------------------------------------
export function checkImages(processEnv, { entry = "soak", defaultImages = {} } = {}) {
  const stale = /master461/;
  const out = [];
  const envImages = ["CALCIFY_DIRECT_IMAGE", "CALCIFY_DIRECT_MATCHING_IMAGE"].filter((k) => processEnv[k]);
  for (const key of envImages) {
    out.push(result(`image:${key}`, stale.test(processEnv[key]) ? FAIL : PASS, key, "not the pinned calcify-10k-master461 tag", processEnv[key],
      "The pinned tag is an old build: compose is invoked without --build, so old code is benchmarked silently."));
  }
  if (entry === "bootstrap" && envImages.length < 2) {
    out.push(result("image:bootstrap-default", FAIL, "bootstrap default images", "explicit CALCIFY_DIRECT_IMAGE + CALCIFY_DIRECT_MATCHING_IMAGE built from this checkout",
      `${defaultImages.runtime ?? "reef-platform-runtime:calcify-10k-master461"} / ${defaultImages.matching ?? "reef-matching-engine:calcify-10k-master461"}`,
      "Running calcify-direct-bootstrap.mjs directly uses the stale default tags without --build. Use make calcify-fullchain-soak (it builds first and sets both)."));
  }
  if (entry === "soak" && processEnv.FC_SKIP_BUILD === "true") {
    out.push(result("image:skip-build", WARN, "FC_SKIP_BUILD", "false (images rebuilt from this checkout)", "true",
      "The soak will reuse whatever images are tagged reef-platform-runtime / reef-matching-engine: stale-code risk after any change."));
  }
  if (out.length === 0) out.push(result("image:build", PASS, "images", "built from this checkout", "soak builds both images unless FC_SKIP_BUILD=true", "no stale tag configured"));
  return out;
}

// ---- Docker VM / host -----------------------------------------------------------------------------------
export function checkDockerVm(docker, { minCpus = 10, minMemGiB = 15 } = {}) {
  if (!docker?.available) {
    return [result("docker:vm", SKIPPED, "Docker VM size", `>= ${minCpus} CPU / ${minMemGiB} GiB`, "(Docker daemon not reachable)",
      "Cannot verify the Docker VM size; the notes' laptop numbers assume 10 CPU / 15.6 GiB.", { blocking: true })];
  }
  const mem = docker.memBytes / GIB;
  const ok = docker.ncpu >= minCpus && mem >= minMemGiB;
  return [result("docker:vm", ok ? PASS : FAIL, "Docker VM size", `>= ${minCpus} CPU / ${minMemGiB} GiB`, `${docker.ncpu} CPU / ${mem.toFixed(1)} GiB`,
    "Every ledger entry assumes a 10 CPU / 15.6 GiB Docker VM (Redpanda + 3 Postgres + Java sidecars + capacity JVM -Xmx8g). A smaller VM starves services and the numbers are misleading.")];
}

// containers: [{ name, ports, labels }]
export function checkRunningStacks(docker, containers, { allow = false, selfProject = null } = {}) {
  if (!docker?.available) {
    return [result("docker:stacks", SKIPPED, "other Reef stacks", "none running", "(Docker daemon not reachable)",
      "Cannot verify the host is quiet (fixed container names and host ports 8080/8081 collide; CPU contention).", { blocking: true })];
  }
  const reef = containers.filter((c) => /^reef[-_]/.test(c.name) && c.project !== selfProject);
  const other = containers.filter((c) => !/^reef[-_]/.test(c.name));
  const out = [];
  out.push(result("docker:stacks", reef.length === 0 ? PASS : allow ? WARN : FAIL, "other Reef stacks", "none running",
    reef.length === 0 ? "none" : reef.map((c) => c.name).join(", "),
    "Quiet-host rule (PERFORMANCE_LEARNINGS Core Learning 3): fixed container names/ports collide and a second stack steals CPU. make dev-down first." + (allow ? " (downgraded by BENCH_ALLOW_RUNNING_STACK=1)" : "")));
  if (other.length > 0) {
    out.push(result("docker:noise", WARN, "non-Reef containers", "none", other.map((c) => c.name).join(", "), "Other running containers add CPU/memory noise to wall-clock rates."));
  }
  return out;
}

export function checkGit(git, { target = "local" } = {}) {
  if (!git?.available) return [result("git:tree", SKIPPED, "git tree", "clean tracked tree", "(git unavailable)", "Cannot record or verify the source revision.")];
  const dirty = git.dirtyTracked.length;
  if (target === "droplet") {
    return [result("git:tree", dirty === 0 ? PASS : FAIL, "tracked tree clean (droplet run)", "0 modified tracked files", String(dirty),
      "Dedicated-host harnesses ship the tree: droplet-chain-run.sh refuses a dirty tracked tree and ships `git archive HEAD`, do-benchmark-host.sh rsyncs everything incl. untracked files. The run must be traceable to one commit.")];
  }
  return [result("git:tree", dirty === 0 ? PASS : WARN, "tracked tree clean", "0 modified tracked files", String(dirty),
    "A local run on a dirty tree is not reproducible from the stamped git sha.")];
}

export function checkHostClass(host, cls) {
  const cpuModel = host.cpuModel ?? "";
  const linux = host.platform === "linux";
  if (cls === "c") {
    const officialShape = linux && host.cpus >= 16;
    return [result("host:class-c", officialShape ? PASS : WARN, "host vs official c-16 gate", "Linux, >= 16 vCPU (DigitalOcean c-16 via do-gate.mjs)",
      `${host.platform}/${host.arch}, ${host.cpus} CPU, ${cpuModel || "unknown CPU"}`,
      officialShape ? "Shape matches c-16; the OFFICIAL number still only comes from do-gate.mjs." :
        "DIAGNOSTIC ONLY, DO NOT QUOTE: laptop numbers overstate target hardware 2-2.7x. The official 10k gate is the DigitalOcean c-16 run (make do-materializer-10k-gate).",
      { diagnosticOnly: !officialShape })];
  }
  if (cls === "b") {
    const epyc = /EPYC/i.test(cpuModel);
    return [result("host:class-b", epyc ? PASS : WARN, "host CPU for finance-lane claims", "AMD EPYC (SHA instructions) for hot-lane claims",
      `${host.platform}/${host.arch}, ${host.cpus} CPU, ${cpuModel || "unknown CPU"}`,
      epyc ? "EPYC host." : "DIAGNOSTIC ONLY, DO NOT QUOTE: no capacity claim follows from a local or Xeon run (Xeon 8168 is 2.6x slower single-thread than EPYC 9555P for SHA-256; laptops overstate 2-2.7x).",
      { diagnosticOnly: !epyc })];
  }
  return [];
}

// ---- Redpanda topics (broker reachable) ------------------------------------------------------------
// topics: [{ name, configs: { 'write.caching': 'false', 'max.message.bytes': '4194304' } }]
export function checkTopics(entries, topics, { eventTopicPattern = /_EVENTS$/, commandTopicPattern = /_COMMANDS$/ } = {}) {
  if (topics === null || topics === undefined) {
    return [result("broker:topics", SKIPPED, "Redpanda topic config", "write.caching=false, max.message.bytes=4 MiB on event topics", "(no broker reachable)",
      "Topic configuration is verified when a broker is running. Bootstrap creates the topics after preflight, so verify again with --overlay/rpk before promoting a number.")];
  }
  const relevant = topics.filter((t) => eventTopicPattern.test(t.name) || commandTopicPattern.test(t.name));
  if (relevant.length === 0) {
    return [result("broker:topics", SKIPPED, "Redpanda topic config", "benchmark topics present", "(no *_COMMANDS / *_EVENTS topic yet)", "Topics are created by the run itself; nothing to inspect yet.")];
  }
  const out = [];
  for (const entry of entries.filter((e) => e.family === "topic")) {
    const config = entry.topicConfig;
    const applies = config === "max.message.bytes" ? relevant.filter((t) => eventTopicPattern.test(t.name)) : relevant;
    for (const topic of applies) {
      const actual = topic.configs?.[config];
      const id = `broker:${topic.name}:${config}`;
      if (actual === undefined) out.push(result(id, SKIPPED, `${topic.name} ${config}`, entry.value, "(not reported)", entry.why));
      else out.push(String(actual) === entry.value ? result(id, PASS, `${topic.name} ${config}`, entry.value, actual, entry.why)
        : result(id, severity(entry), `${topic.name} ${config}`, entry.value, actual, `${entry.why} Fix: rpk topic alter-config ${topic.name} --set ${config}=${entry.value}`));
    }
  }
  return out;
}

// ---- Class A tooling -----------------------------------------------------------------------------------------
const semver = (text) => (/(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(text) ?? []).slice(1, 4).map((n) => Number(n ?? 0));
export function compareVersion(a, b) {
  const x = Array.isArray(a) ? a : semver(a);
  const y = Array.isArray(b) ? b : semver(b);
  for (let i = 0; i < 3; i += 1) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0);
  return 0;
}

export function checkTools(tools) {
  const out = [];
  if (!tools.go) out.push(result("tool:go", FAIL, "go", `>= ${tools.goRequired ?? "go.mod version"}`, "(not installed)", "The matching-engine guardrail needs the Go toolchain from services/matching-engine/go.mod."));
  else if (tools.goRequired && compareVersion(tools.go, tools.goRequired) < 0) out.push(result("tool:go", FAIL, "go", `>= ${tools.goRequired}`, tools.go, "Older Go than go.mod (CI uses the go.mod version)."));
  else out.push(result("tool:go", PASS, "go", `>= ${tools.goRequired ?? "?"}`, tools.go, "toolchain present"));
  if (!tools.java) out.push(result("tool:java", FAIL, "java", ">= 21", "(not installed)", "The platform-runtime guardrail runs through Gradle on a JDK."));
  else if (compareVersion(tools.java, "21") < 0) out.push(result("tool:java", FAIL, "java", ">= 21", tools.java, "CI uses JDK 21."));
  else out.push(result("tool:java", compareVersion(tools.java, "22") >= 0 ? WARN : PASS, "java", "21 (CI)", tools.java,
    compareVersion(tools.java, "22") >= 0 ? "newer than the CI JDK 21; ns/op may differ slightly from CI" : "matches CI"));
  return out;
}

// ---- verdict ------------------------------------------------------------------------------------------------------------
export function verdict(results) {
  if (results.some((r) => r.status === FAIL)) return { status: "FAIL", exitCode: 1 };
  if (results.some((r) => r.status === SKIPPED && r.blocking)) return { status: "COULD_NOT_CHECK", exitCode: 2 };
  return { status: "PASS", exitCode: 0 };
}
