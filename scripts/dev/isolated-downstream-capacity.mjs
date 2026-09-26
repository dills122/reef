#!/usr/bin/env node
// Isolated diagnostic only. No server/worker settings or production functions are changed.
import { spawn, execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync, appendFileSync, existsSync } from "node:fs";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";

const BATCH = 500;
const WARMUP = 3;
const CYCLES = 30;
const MAX_CALLS = 100_001;
const DECIMAL = /^(0|[1-9][0-9]*)$/;
const generationSql = "to_char(pg_postmaster_start_time() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
const clientsSql = "(SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid())";
const usage = `Usage: node scripts/dev/isolated-downstream-capacity.mjs --stage=lifecycle|market --container=NAME --project=COMPOSE_PROJECT --manifest=FILE --out=NEW_DIRECTORY [--db=reef] [--user=reef] [--projection-name=market-data-top-of-book] [--source-projection-name=MATCH_MANIFEST]
Manifest: {"fixtureId":"...","sourceProjectionName":"...","sourceCommands":"...","sourceMax":"...","instrumentIds":[64 unique strings for market]}.
Requires stopped platform-* services in selected Compose project and no other clients on target DB.
Batch500; market3warmup+30timed cycles per invocation. Parent orchestrates repetitions/restores.
Artifacts include stdout/stderr, SQL, before/after snapshots, per-call results and report. No state-equivalence promotion is inferred.`;

function check(condition, message) { if (!condition) throw Error(message); }
function decimal(value, name) {
  check(typeof value === "string" && DECIMAL.test(value), `${name} must be exact unsigned decimal string`);
  return BigInt(value);
}
function hexText(value) {
  return `convert_from(decode('${Buffer.from(value, "utf8").toString("hex")}', 'hex'), 'UTF8')`;
}
export function validateManifest(manifest, stage) {
  check(manifest && typeof manifest.fixtureId === "string" && manifest.fixtureId.trim(), "fixtureId required");
  check(typeof manifest.sourceProjectionName === "string" && manifest.sourceProjectionName.length > 0 && !manifest.sourceProjectionName.includes("\0"), "sourceProjectionName required in manifest");
  check(decimal(manifest.sourceCommands, "sourceCommands") > 0n, "sourceCommands must be positive");
  check(decimal(manifest.sourceMax, "sourceMax") <= 9223372036854775807n, "sourceMax exceeds PostgreSQL bigint");
  check(["lifecycle", "market"].includes(stage), "stage must be lifecycle or market");
  if (stage === "market") {
    check(Array.isArray(manifest.instrumentIds) && manifest.instrumentIds.length === 64 &&
      manifest.instrumentIds.every(id => typeof id === "string" && id.length > 0 && !id.includes("\0")) &&
      new Set(manifest.instrumentIds).size === 64, "market manifest requires exactly64 unique nonempty instrument IDs");
  }
  return manifest;
}

export function validateContainers(containers, targetId, project) {
  const target = containers.find(c => c.Id === targetId);
  check(target?.State?.Running && target.Config?.Labels?.["com.docker.compose.project"] === project &&
    target.Config?.Labels?.["com.docker.compose.service"] === "projection-postgres", "selected running projection-postgres must match explicit project");
  check(!containers.some(c => c.State?.Running && /^platform-/.test(c.Config?.Labels?.["com.docker.compose.service"] ?? "")), "platform runtime callers must all be stopped");
}

function snapshotSql(options, label) {
  return `\\echo TASK2_SNAPSHOT ${label}
SELECT json_build_object(
 'databaseGeneration', ${generationSql},
 'lifecyclePending', (SELECT count(*)::text FROM runtime.order_lifecycle_dirty),
 'marketPending', (SELECT count(*)::text FROM runtime.market_data_snapshot_dirty),
 'otherClients', ${clientsSql}::text,
 'sourceMax', (SELECT coalesce(max(last_partition_seq) FILTER (WHERE partition_id >= 0),0)::text FROM runtime.projection_watermarks WHERE projection_name = ${hexText(options.sourceProjectionName)}),
 'watermarks', (SELECT coalesce(json_agg(json_build_object('partition',partition_id,'sequence',last_partition_seq::text,'error',last_error) ORDER BY partition_id),'[]'::json) FROM runtime.projection_watermarks WHERE projection_name = ${hexText(options.sourceProjectionName)}),
 'sourceFacts', json_build_object('orders',(SELECT count(*)::text FROM runtime.orders),'executions',(SELECT count(*)::text FROM runtime.executions),'trades',(SELECT count(*)::text FROM runtime.trades),'runtimeEvents',(SELECT count(*)::text FROM runtime.runtime_events))
);
\\echo TASK2_END_SNAPSHOT ${label}`;
}
function timedCall(phase, index, body, precedingCte = "") {
  return `\\echo TASK2_BEGIN ${phase} ${index}
WITH ${precedingCte}result AS MATERIALIZED (${body})
SELECT drained::text AS rows, (drained = 0) AS zero FROM result
\\gset task2_
\\echo TASK2_END ${phase} ${index} :task2_rows`;
}
function validateBefore(options, manifest, before) {
  validateManifest(manifest, options.stage);
  check(options.sourceProjectionName === manifest.sourceProjectionName, "source projection must match manifest exactly");
  check(before && typeof before.databaseGeneration === "string" && before.databaseGeneration.length > 0, "database generation required");
  check(before.otherClients === "0", "unknown database clients present");
  check(before.sourceMax === manifest.sourceMax, "sourceMax does not match fixture manifest");
  check(Array.isArray(before.watermarks) && before.watermarks.some(w => w.partition >= 0) && before.watermarks.every(w => w.error === ""), "missing or errored source watermarks");
  decimal(before.lifecyclePending, "lifecyclePending"); decimal(before.marketPending, "marketPending");
  if (options.stage === "lifecycle") check(BigInt(before.lifecyclePending) > 0n, "initial lifecycle dirty queue must be nonempty");
  else check(before.lifecyclePending === "0" && before.marketPending === "0", "market fixture requires both queues initially empty");
}
export function buildWorkloadSql(options, manifest, before) {
  validateBefore(options, manifest, before);
  const parts = ["\\timing on"];
  if (options.stage === "lifecycle") {
    const count = (BigInt(before.lifecyclePending) + 499n) / 500n + 1n;
    check(count <= BigInt(MAX_CALLS), "initial dirty exceeds bounded runner call limit");
    parts.push("\\set keep_running true");
    for (let index = 1; index <= Number(count); index++) parts.push(
      "\\if :keep_running",
      timedCall("lifecycle", index, `SELECT runtime.runtime_project_order_lifecycle_state(${BATCH}) AS drained`),
      "\\if :task2_zero", "\\set keep_running false", "\\endif", "\\endif",
    );
  } else {
    const ids = `${hexText(JSON.stringify(manifest.instrumentIds))}::jsonb`;
    for (let index = 1; index <= WARMUP + CYCLES; index++) {
      if (index === WARMUP + 1) parts.push("\\echo TASK2_TIMED_START");
      parts.push(timedCall("redirty", index, "SELECT count(*) AS drained FROM inserted", `inserted AS (
 INSERT INTO runtime.market_data_snapshot_dirty(instrument_id)
 SELECT value FROM jsonb_array_elements_text(${ids}) ORDER BY value
 ON CONFLICT (instrument_id) DO NOTHING RETURNING instrument_id
), `));
      parts.push(timedCall("market", index, `SELECT runtime.runtime_project_market_data_snapshots(${hexText(options.projectionName)}, ${hexText(options.sourceProjectionName)}, '${manifest.sourceMax}'::bigint, 0::bigint, ${BATCH}) AS drained`));
    }
  }
  parts.push("\\echo TASK2_FINISHED", "\\timing off", snapshotSql(options, "after"), "\\echo TASK2_DONE");
  return parts.join("\n") + "\n";
}

// Strict framing: row output alone is insufficient; timing arrives only after libpq completion.
export function parseTranscript(text, { partial = false } = {}) {
  const result = { calls: [] };
  let snapshot = null, current = null, ready = false, finished = false, done = false;
  const indices = new Map();
  for (const line of text.split(/\r?\n/)) {
    if (!line || /^Timing is (on|off)\.$/.test(line)) continue;
    if (snapshot) {
      if (line === `TASK2_END_SNAPSHOT ${snapshot}`) { check(result[snapshot] !== undefined, "snapshot result missing"); snapshot = null; continue; }
      check(result[snapshot] === undefined, "duplicate snapshot row");
      result[snapshot] = JSON.parse(line); continue;
    }
    let match;
    if ((match = /^TASK2_SNAPSHOT (before|after)$/.exec(line))) {
      check(!current && !done && !result[match[1]] && (match[1] === "before" ? !ready : finished), "snapshot out of order");
      snapshot = match[1]; continue;
    }
    if (line === "TASK2_READY") { check(result.before && !ready && !current, "unexpected ready marker"); ready = true; continue; }
    if (line === "TASK2_TIMED_START") { check(ready && !finished && !current && !result.timedStart && result.calls.length === WARMUP * 2, "unexpected timed start"); result.timedStart = true; continue; }
    if (line === "TASK2_FINISHED") { check(ready && !finished && !current, "unexpected finished marker"); finished = true; continue; }
    if (line === "TASK2_DONE") { check(finished && result.after && !done && !current, "unexpected done marker"); done = true; continue; }
    if ((match = /^TASK2_BEGIN (lifecycle|redirty|market) ([1-9][0-9]*)$/.exec(line))) {
      const index = Number(match[2]);
      check(ready && !finished && !current && index === (indices.get(match[1]) ?? 0) + 1, "call order or duplicate begin");
      current = { phase: match[1], index }; indices.set(match[1], index); continue;
    }
    if ((match = /^Time: ([0-9]+(?:\.[0-9]+)?) ms(?: \([^\r\n]*\))?$/.exec(line))) {
      check(current && current.psqlStatementMs === undefined && Number.isFinite(Number(match[1])), "missing call or duplicate timing");
      current.psqlStatementMs = Number(match[1]); continue;
    }
    if ((match = /^TASK2_END (lifecycle|redirty|market) ([1-9][0-9]*) (0|[1-9][0-9]*)$/.exec(line))) {
      check(current && current.phase === match[1] && current.index === Number(match[2]) && current.psqlStatementMs !== undefined, "mismatched end or missing timing");
      result.calls.push({ ...current, rows: match[3] }); current = null; continue;
    }
    throw Error(`unrecognized psql output: ${line.slice(0, 120)}`);
  }
  check(!current && !snapshot && ready && (partial || done), "truncated psql transcript");
  return result;
}

export function summarizeCapacity(options, manifest, parsed, clientPhaseWallMs, timedPhaseWallMs = clientPhaseWallMs) {
  validateBefore(options, manifest, parsed.before);
  const { before, after, calls } = parsed;
  check(after && after.databaseGeneration === before.databaseGeneration, "database generation changed");
  check(after.otherClients === "0", "foreign database client after run");
  check(after.sourceMax === before.sourceMax && JSON.stringify(after.watermarks) === JSON.stringify(before.watermarks) && JSON.stringify(after.sourceFacts) === JSON.stringify(before.sourceFacts), "source state drift");
  check(after.lifecyclePending === "0", "lifecycle queue not empty");
  check(Number.isFinite(clientPhaseWallMs) && clientPhaseWallMs > 0 && Number.isFinite(timedPhaseWallMs) && timedPhaseWallMs > 0, "invalid client timing");
  const sum = rows => rows.reduce((n, c) => n + BigInt(c.rows), 0n);
  const millis = rows => rows.reduce((n, c) => n + c.psqlStatementMs, 0);
  const base = { schemaVersion: "reef.isolatedDownstreamCapacity.v1", pass: true, stage: options.stage, fixtureId: manifest.fixtureId, batchSize: BATCH,
    sourceCommands: { declared: manifest.sourceCommands, verifiedByRunner: false, equivalentToDirtyWork: false },
    sourceMax: manifest.sourceMax, timingScope: "single SQL statement through acknowledged autocommit; excludes setup and pre/post diagnostic guards", callerEvidence: "one persistent psql; no platform containers or other DB clients at pre/post boundaries; dedicated fixture isolation required", staticBusinessDigestVerifiedByRunner: false, clientPhaseWallMs, before, after, calls };
  if (options.stage === "lifecycle") {
    check(calls.length > 1 && calls.length <= Number((BigInt(before.lifecyclePending) + 499n) / 500n + 1n), "lifecycle call bound violated");
    check(calls.every((c, i) => c.phase === "lifecycle" && c.index === i + 1 && (i === calls.length - 1 ? c.rows === "0" : BigInt(c.rows) > 0n && BigInt(c.rows) <= 500n)), "lifecycle counts, zero or phases invalid");
    check(sum(calls) === BigInt(before.lifecyclePending), "drained orders do not equal initial dirty count");
    const productive = calls.slice(0, -1), sqlMs = millis(productive);
    check(sqlMs > 0, "productive SQL elapsed must be positive");
    return { ...base, dirtyOrders: sum(calls).toString(), productiveCalls: productive.length, productiveStatementMs: sqlMs,
      zeroConfirmationMs: calls.at(-1).psqlStatementMs, dirtyOrdersPerSqlSecond: Number(sum(calls)) * 1000 / sqlMs,
      dirtyOrdersPerWallSecond: Number(sum(calls)) * 1000 / clientPhaseWallMs };
  }
  check(after.marketPending === "0", "market queue not empty");
  check(parsed.timedStart === true, "missing market warmup boundary");
  check(calls.length === 2 * (WARMUP + CYCLES) && calls.every((c, i) => c.phase === (i % 2 ? "market" : "redirty") && c.index === Math.floor(i / 2) + 1 && c.rows === "64"), "market cycle order or exact64 counts invalid");
  const timed = calls.slice(WARMUP * 2), marketMs = millis(timed.filter(c => c.phase === "market")), redirtyMs = millis(timed.filter(c => c.phase === "redirty"));
  check(marketMs > 0 && redirtyMs > 0, "market timings must be positive");
  return { ...base, warmupCycles: WARMUP, timedCycles: CYCLES, uniqueInstruments: 64, dirtyInstrumentOperations: String(CYCLES * 64),
    marketStatementMs: marketMs, redirtyStatementMs: redirtyMs, timedPhaseWallMs,
    dirtyInstrumentsPerSqlSecond: CYCLES * 64 * 1000 / marketMs,
    completeCyclesPerWallSecond: CYCLES * 1000 / timedPhaseWallMs };
}

function inspectProject(options) {
  const target = JSON.parse(execFileSync("docker", ["inspect", options.container], { encoding: "utf8" }))[0];
  const ids = execFileSync("docker", ["ps", "-aq", "--filter", `label=com.docker.compose.project=${options.project}`], { encoding: "utf8" }).trim().split(/\s+/).filter(Boolean);
  check(ids.length, "Compose project has no containers");
  const containers = JSON.parse(execFileSync("docker", ["inspect", ...ids], { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 }));
  validateContainers(containers, target.Id, options.project);
  return containers.map(c => ({ id: c.Id, service: c.Config.Labels["com.docker.compose.service"], running: c.State.Running, imageId: c.Image })).sort((a,b) => a.id.localeCompare(b.id));
}

async function run(options, manifest) {
  check(!existsSync(options.out), "output directory must not exist (preserve previous evidence)");
  mkdirSync(options.out, { recursive: true });
  const save = (name, value) => writeFileSync(join(options.out, name), JSON.stringify(value, null, 2) + "\n");
  save("manifest.json", manifest); save("options.json", options);
  writeFileSync(join(options.out, "stdout.txt"), ""); writeFileSync(join(options.out, "stderr.txt"), "");
  try {
    const containersBefore = inspectProject(options); save("containers-before.json", containersBefore);
    const preamble = `\\set AUTOCOMMIT on\n\\set FETCH_COUNT 0\n\\pset pager off\nSET timezone = 'UTC';\nSET application_name = 'reef-task2-${options.stage}';\nSET statement_timeout = '60s';\n${snapshotSql(options, "before")}\n\\echo TASK2_READY\n`;
    writeFileSync(join(options.out, "session.sql"), preamble);
    const parsedAndTimes = await new Promise((resolveRun, reject) => {
      const child = spawn("docker", ["exec", "-i", "-e", "LC_ALL=C", options.container, "psql", "-X", "-q", "-A", "-t", "-w", "-U", options.user, "-d", options.db, "-v", "ON_ERROR_STOP=1"], { stdio: ["pipe", "pipe", "pipe"] });
      let stdout = "", stderr = "", sent = false, start = null, timedStart = null, finish = null, failure = null, pendingTimedSql = null;
      const timeout = setTimeout(() => { failure = Error("runner timed out"); child.kill("SIGTERM"); }, 30 * 60 * 1000);
      const fail = error => { failure ??= error; child.stdin.destroy(); child.kill("SIGTERM"); };
      child.on("error", fail); child.stdin.on("error", fail);
      child.stdout.on("data", chunk => {
        appendFileSync(join(options.out, "stdout.txt"), chunk); stdout += chunk.toString();
        try {
          if (!sent && stdout.includes("TASK2_READY\n")) {
            const before = parseTranscript(stdout.slice(0, stdout.indexOf("TASK2_READY\n") + "TASK2_READY\n".length), { partial: true }).before;
            save("before.json", before);
            const sql = buildWorkloadSql(options, manifest, before); appendFileSync(join(options.out, "session.sql"), sql);
            sent = true; start = performance.now();
            const boundary = "\\echo TASK2_TIMED_START\n";
            if (options.stage === "market") {
              const split = sql.indexOf(boundary) + boundary.length;
              check(split >= boundary.length, "missing generated warmup boundary");
              pendingTimedSql = sql.slice(split); child.stdin.write(sql.slice(0, split));
            } else child.stdin.end(sql);
          }
          if (pendingTimedSql !== null && stdout.includes("TASK2_TIMED_START\n")) {
            // Handshake avoids measuring a buffered marker's arrival as server phase start.
            timedStart = performance.now(); child.stdin.end(pendingTimedSql); pendingTimedSql = null;
          }
          if (finish === null && stdout.includes("TASK2_FINISHED\n")) finish = performance.now();
        } catch (error) { fail(error); }
      });
      child.stderr.on("data", chunk => { appendFileSync(join(options.out, "stderr.txt"), chunk); stderr += chunk.toString(); });
      child.on("close", (code, signal) => {
        clearTimeout(timeout);
        if (failure) return reject(failure);
        if (code !== 0 || signal || stderr.trim()) return reject(Error(`psql failed or emitted stderr (exit=${code}, signal=${signal}); inspect raw artifacts`));
        try { check(start !== null && finish !== null, "missing measurement phase markers");
          const parsed = parseTranscript(stdout); save("after.json", parsed.after);
          resolveRun({ parsed, wallMs: finish - start, timedWallMs: finish - (timedStart ?? start) });
        } catch (error) { reject(error); }
      });
      child.stdin.write(preamble);
    });
    const containersAfter = inspectProject(options); save("containers-after.json", containersAfter);
    check(JSON.stringify(containersBefore) === JSON.stringify(containersAfter), "container state changed during measurement");
    const result = summarizeCapacity(options, manifest, parsedAndTimes.parsed, parsedAndTimes.wallMs, parsedAndTimes.timedWallMs);
    result.manifestSha256 = createHash("sha256").update(JSON.stringify(manifest)).digest("hex");
    save("before.json", result.before); save("after.json", result.after); save("report.json", result);
    console.log(JSON.stringify({ pass: true, stage: options.stage, report: join(options.out, "report.json") }));
  } catch (error) {
    save("report.json", { schemaVersion: "reef.isolatedDownstreamCapacity.v1", pass: false, stage: options.stage, error: error.message });
    throw error;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.includes("--help")) { console.log(usage); process.exit(0); }
    const args = {};
    for (const arg of process.argv.slice(2)) {
      const match = /^--([a-z-]+)=(.+)$/.exec(arg); check(match && !(match[1] in args), "use unique --key=value arguments"); args[match[1]] = match[2];
    }
    const known = new Set(["stage", "container", "project", "manifest", "out", "db", "user", "projection-name", "source-projection-name"]);
    check(Object.keys(args).every(key => known.has(key)), "unknown argument");
    for (const key of ["stage", "container", "project", "manifest", "out"]) check(args[key], `--${key} required`);
    for (const key of ["container", "project", "db", "user"]) if (args[key]) check(/^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(args[key]), `invalid ${key}`);
    const options = { stage: args.stage, container: args.container, project: args.project, out: resolve(args.out), db: args.db ?? "reef", user: args.user ?? "reef",
      projectionName: args["projection-name"] ?? "market-data-top-of-book", sourceProjectionName: args["source-projection-name"] };
    const manifest = validateManifest(JSON.parse(readFileSync(resolve(args.manifest), "utf8")), options.stage);
    options.sourceProjectionName ??= manifest.sourceProjectionName;
    check(options.sourceProjectionName === manifest.sourceProjectionName, "source projection must match manifest exactly");
    for (const value of [options.projectionName, options.sourceProjectionName]) check(value.length > 0 && !value.includes("\0"), "invalid projection name");
    await run(options, manifest);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
