#!/usr/bin/env node
// Generate reference SQL from the checked-in Kotlin implementation; never execute DB commands.
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { resolve, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SOURCE = "services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt";
const TABLES = {
  orderLifecycleState: "runtime.order_lifecycle_state", marketDataSnapshots: "runtime.market_data_snapshots",
  orders: "runtime.orders", executions: "runtime.executions", runtimeEvents: "runtime.runtime_events",
};
const METHOD = { lifecycle: "rebuildOrderLifecycleState", market: "refreshMarketDataSnapshots" };
const usage = `Generate (no DB access): node scripts/dev/downstream-state-reference.mjs --stage=lifecycle|market|both --manifest=FILE --out=NEW_DIR [--projection-name=market-data-top-of-book]
Manifest requires fixtureId, sourceProjectionName, sourceMax as exact decimal string; market also requires sourceLag:"0" (parent-verified canonical lag).
Execute generated reference.sql with explicitly selected isolated projection DB: psql -X -q -A -t -v ON_ERROR_STOP=1 < reference.sql > result.ndjson 2> stderr.log.
Verify after successful psql exit: node scripts/dev/downstream-state-reference.mjs --verify=result.ndjson --stage=lifecycle|market|both.
SQL temporarily rebuilds only projections, compares every field except updated_at, and ROLLBACKs. Missing rollback completion or any mismatch fails verification.`;
const check = (condition, message) => { if (!condition) throw Error(message); };
const hash = value => createHash("sha256").update(value).digest("hex");
const textLiteral = value => `convert_from(decode('${Buffer.from(value, "utf8").toString("hex")}', 'hex'), 'UTF8')`;
function exactLong(value, name) {
  check(typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value) && BigInt(value) <= 9223372036854775807n, `${name} requires exact unsigned bigint string`);
  return `${value}::bigint`;
}
function trimIndent(value) {
  const lines = value.replace(/^\r?\n/, "").replace(/\r?\n\s*$/, "").split("\n");
  const indent = Math.min(...lines.filter(line => line.trim()).map(line => /^\s*/.exec(line)[0].length));
  return lines.map(line => line.slice(indent)).join("\n");
}
function tables(sql) {
  const expanded = sql.replace(/\$\{names\.([A-Za-z]+)\}/g, (_, name) => {
    check(TABLES[name], `unreviewed table interpolation: ${name}`); return TABLES[name];
  });
  check(!expanded.includes("${"), "unsupported Kotlin interpolation");
  return expanded;
}
export function extractReferenceSql(source, stage, manifest, projectionName = "market-data-top-of-book") {
  check(METHOD[stage], "unsupported reference stage");
  const marker = `    override fun ${METHOD[stage]}(`;
  const start = source.indexOf(marker);
  check(start >= 0 && source.indexOf(marker, start + 1) < 0, "reference method missing or duplicated");
  const next = source.indexOf("\n    override fun ", start + marker.length);
  check(next > start, "reference method boundary missing");
  const method = source.slice(start, next);
  const templates = [...method.matchAll(/"""([\s\S]*?)"""\.trimIndent\(\)/g)];
  const deletes = [...method.matchAll(/conn\.prepareStatement\("(DELETE FROM [^"]+)"\)/g)];
  check(templates.length === 1 && deletes.length === 1 && (method.match(/conn\.prepareStatement\(/g) ?? []).length === 2, "reference SQL method shape changed");
  const template = trimIndent(templates[0][1]);
  const target = stage === "lifecycle" ? "orderLifecycleState" : "marketDataSnapshots";
  check((template.match(/INSERT INTO /g) ?? []).length === 1 && template.includes(`INSERT INTO \${names.${target}}(`), "reference insert target changed");
  check(!/\b(DELETE FROM|UPDATE\s|TRUNCATE|MERGE INTO)\b/.test(template), "unexpected mutation in reference insert");
  const expectedDelete = stage === "lifecycle" ? "DELETE FROM ${names.orderLifecycleState}" : "DELETE FROM ${names.marketDataSnapshots} WHERE projection_name = ?";
  check(deletes[0][1] === expectedDelete, "reference delete target changed");
  let insert = tables(template), deletion = tables(deletes[0][1]);
  if (stage === "market") {
    for (const binding of ["ps.setString(1, projectionName)", "ps.setString(2, sourceProjectionName)", "ps.setLong(3, lastPartitionSequence)", "ps.setLong(4, sourceStatus.lag)"]) {
      check(method.includes(binding), `reference parameter binding changed: ${binding}`);
    }
    check(manifest.sourceLag === "0", "market reference requires parent-verified sourceLag string zero");
    const values = [textLiteral(projectionName), textLiteral(manifest.sourceProjectionName), exactLong(manifest.sourceMax, "sourceMax"), "0::bigint"];
    let index = 0;
    insert = insert.replace(/^(\s*)\?,\s*$/gm, (_, indent) => `${indent}${values[index++]},`);
    check(index === values.length, "reference placeholder count changed");
    deletion = deletion.replace("?", textLiteral(projectionName));
  }
  return { insert, delete: deletion, provenance: { sourcePath: SOURCE, method: METHOD[stage],
    methodLine: source.slice(0, start).split("\n").length, sourceSha256: hash(source), methodSha256: hash(method),
    templateSha256: hash(template), expandedInsertSha256: hash(insert), parameterOrderVerified: stage === "market" } };
}
function comparison(stage, target, filter = "") {
  return `CREATE TEMP TABLE reference_${stage}_after ON COMMIT DROP AS
SELECT to_jsonb(state_row) - 'updated_at' AS business FROM ${target} state_row ${filter};
WITH missing AS MATERIALIZED (
 SELECT business FROM reference_${stage}_before EXCEPT ALL SELECT business FROM reference_${stage}_after
), extra AS MATERIALIZED (
 SELECT business FROM reference_${stage}_after EXCEPT ALL SELECT business FROM reference_${stage}_before
)
SELECT json_build_object('stage','${stage}',
 'incrementalRows',(SELECT count(*)::text FROM reference_${stage}_before),
 'referenceRows',(SELECT count(*)::text FROM reference_${stage}_after),
 'missingFromReference',(SELECT count(*)::text FROM missing),
 'extraInReference',(SELECT count(*)::text FROM extra),
 'pass',NOT EXISTS(SELECT 1 FROM missing) AND NOT EXISTS(SELECT 1 FROM extra),
 'missingExamples',(SELECT coalesce(jsonb_agg(business),'[]'::jsonb) FROM (SELECT business FROM missing ORDER BY business::text LIMIT 5) examples),
 'extraExamples',(SELECT coalesce(jsonb_agg(business),'[]'::jsonb) FROM (SELECT business FROM extra ORDER BY business::text LIMIT 5) examples)
);`;
}
export function buildReferenceScript(source, options, manifest) {
  check(["lifecycle", "market", "both"].includes(options.stage), "stage required");
  check(manifest && typeof manifest.fixtureId === "string" && manifest.fixtureId.length && typeof manifest.sourceProjectionName === "string" && manifest.sourceProjectionName.length, "fixture/source projection names required");
  exactLong(manifest.sourceMax, "sourceMax");
  const projectionName = options.projectionName ?? "market-data-top-of-book";
  check(typeof projectionName === "string" && projectionName.length && !projectionName.includes("\0") && !manifest.sourceProjectionName.includes("\0"), "projection name invalid");
  const stages = options.stage === "both" ? ["lifecycle", "market"] : [options.stage];
  const references = stages.map(stage => ({ stage, ...extractReferenceSql(source, stage, manifest, projectionName) }));
  const sourceName = textLiteral(manifest.sourceProjectionName);
  const parts = ["\\set ON_ERROR_STOP on", "\\set AUTOCOMMIT on", "\\set FETCH_COUNT 0", "\\pset pager off", "\\echo REFERENCE_STARTED",
    "BEGIN ISOLATION LEVEL REPEATABLE READ;", "SET LOCAL statement_timeout = '5min';", "SET LOCAL lock_timeout = '5s';",
    "LOCK TABLE runtime.orders, runtime.executions, runtime.runtime_events, runtime.projection_watermarks IN SHARE MODE NOWAIT;",
    "LOCK TABLE runtime.order_lifecycle_state, runtime.market_data_snapshots, runtime.order_lifecycle_dirty, runtime.market_data_snapshot_dirty IN EXCLUSIVE MODE NOWAIT;",
    `DO $reef_reference$ BEGIN
 IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend' AND pid<>pg_backend_pid()) THEN
   RAISE EXCEPTION 'other database clients present'; END IF;
 IF NOT EXISTS (SELECT 1 FROM runtime.projection_watermarks WHERE projection_name=${sourceName} AND partition_id>=0)
 OR EXISTS (SELECT 1 FROM runtime.projection_watermarks WHERE projection_name=${sourceName} AND last_error<>'')
 OR (SELECT max(last_partition_seq) FROM runtime.projection_watermarks WHERE projection_name=${sourceName} AND partition_id>=0) <> ${exactLong(manifest.sourceMax, "sourceMax")} THEN
   RAISE EXCEPTION 'source frontier missing, errored or different'; END IF;
 IF EXISTS (SELECT 1 FROM runtime.order_lifecycle_dirty) THEN RAISE EXCEPTION 'lifecycle queue not empty'; END IF;
 ${stages.includes("market") ? "IF EXISTS (SELECT 1 FROM runtime.market_data_snapshot_dirty) THEN RAISE EXCEPTION 'market queue not empty'; END IF;" : ""}
END $reef_reference$;`,
  ];
  // Snapshot BOTH incremental outputs before replacing either reference projection.
  for (const { stage } of references) {
    const target = stage === "lifecycle" ? TABLES.orderLifecycleState : TABLES.marketDataSnapshots;
    const filter = stage === "market" ? `WHERE projection_name=${textLiteral(projectionName)}` : "";
    parts.push(`CREATE TEMP TABLE reference_${stage}_before ON COMMIT DROP AS\nSELECT to_jsonb(state_row) - 'updated_at' AS business FROM ${target} state_row ${filter};`);
  }
  for (const ref of references) {
    const target = ref.stage === "lifecycle" ? TABLES.orderLifecycleState : TABLES.marketDataSnapshots;
    const filter = ref.stage === "market" ? `WHERE projection_name=${textLiteral(projectionName)}` : "";
    parts.push(ref.delete + ";", ref.insert + ";", comparison(ref.stage, target, filter));
  }
  parts.push("ROLLBACK;", "\\echo REFERENCE_ROLLBACK_COMPLETE");
  return { sql: parts.join("\n") + "\n", provenance: { schemaVersion: "reef.downstreamStateReference.v1", stage: options.stage,
    fixtureId: manifest.fixtureId, sourceProjectionName: manifest.sourceProjectionName, sourceMax: manifest.sourceMax,
    sourceLag: manifest.sourceLag ?? null, sourceLagVerifiedByHelper: false, projectionName,
    excludedColumns: ["updated_at"], transaction: "rollback-only", references: references.map(ref => ref.provenance) } };
}
export function verifyReferenceOutput(text, stage) {
  const expected = stage === "both" ? ["lifecycle", "market"] : [stage];
  check(expected.every(name => METHOD[name]), "stage required");
  const lines = text.trim().split(/\r?\n/);
  check(lines.shift() === "REFERENCE_STARTED" && lines.pop() === "REFERENCE_ROLLBACK_COMPLETE", "reference execution/rollback incomplete");
  const results = lines.filter(Boolean).map(line => JSON.parse(line));
  check(results.length === expected.length, "reference result count mismatch");
  results.forEach((result, i) => {
    check(result.stage === expected[i] && result.pass === true && result.missingFromReference === "0" && result.extraInReference === "0" &&
      typeof result.incrementalRows === "string" && /^(0|[1-9][0-9]*)$/.test(result.incrementalRows) && result.incrementalRows === result.referenceRows && BigInt(result.incrementalRows) > 0n,
    `reference business state mismatch: ${expected[i]}`);
  });
  return { pass: true, excludedColumns: ["updated_at"], rollbackObserved: true, results };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.includes("--help")) { console.log(usage); process.exit(0); }
    const args = {};
    for (const arg of process.argv.slice(2)) { const match = /^--([a-z-]+)=(.+)$/.exec(arg); check(match && !Object.hasOwn(args, match[1]), "unique --key=value required"); args[match[1]] = match[2]; }
    check(Object.keys(args).every(key => ["stage", "manifest", "out", "projection-name", "verify"].includes(key)), "unknown argument");
    if (args.verify) console.log(JSON.stringify(verifyReferenceOutput(readFileSync(resolve(args.verify), "utf8"), args.stage), null, 2));
    else {
      check(args.manifest && args.out, "--manifest and --out required");
      const sourcePath = fileURLToPath(new URL(`../../${SOURCE}`, import.meta.url));
      const source = readFileSync(sourcePath, "utf8");
      const result = buildReferenceScript(source, { stage: args.stage, projectionName: args["projection-name"] }, JSON.parse(readFileSync(resolve(args.manifest), "utf8")));
      const out = resolve(args.out); check(!existsSync(out), "output directory must not exist"); mkdirSync(out, { recursive: true });
      writeFileSync(join(out, "reference.sql"), result.sql);
      writeFileSync(join(out, "provenance.json"), JSON.stringify(result.provenance, null, 2) + "\n");
      console.log(JSON.stringify({ generated: true, executed: false, sql: join(out, "reference.sql"), provenance: join(out, "provenance.json") }));
    }
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
