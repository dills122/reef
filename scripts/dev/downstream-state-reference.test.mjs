import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { extractReferenceSql, buildReferenceScript, verifyReferenceOutput } from "./downstream-state-reference.mjs";

const source = readFileSync(new URL("../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt", import.meta.url), "utf8");
const manifest = { fixtureId: "fixture", sourceProjectionName: "runtime-normalized-submit", sourceMax: "4222124650669353", sourceLag: "0" };

test("extracts exact current full-rebuild templates and binds prepared parameters without rewriting expressions", () => {
  const sql = extractReferenceSql(source, "lifecycle", manifest);
  assert.match(sql.insert, /BOOL_OR\(event_type = 'OrderRejected'\)/);
  assert.match(sql.insert, /FROM runtime\.orders orders/);
  assert.match(sql.insert, /quantity_units ~ '\^\[0-9\]/);
  assert.equal(sql.delete, "DELETE FROM runtime.order_lifecycle_state");
  const market = extractReferenceSql(source, "market", manifest);
  assert.match(market.insert, /4222124650669353::bigint/);
  assert.match(market.insert, /FROM runtime\.order_lifecycle_state/);
  assert.doesNotMatch(market.insert, /\$\{names\.|^\s*\?,/m);
  assert.equal(sql.provenance.templateSha256.length, 64);
  assert.equal(market.provenance.parameterOrderVerified, true);
});

test("reference compares full business row multisets excluding only updated_at and always rolls back", () => {
  const { sql } = buildReferenceScript(source, { stage: "both" }, manifest);
  assert.equal((sql.match(/EXCEPT ALL/g) ?? []).length, 4);
  assert.equal((sql.match(/to_jsonb\(state_row\) - 'updated_at'/g) ?? []).length, 4);
  assert.match(sql, /LOCK TABLE .* IN SHARE MODE NOWAIT/s);
  assert.match(sql, /ON COMMIT DROP/);
  assert.match(sql, /ROLLBACK;\n\\echo REFERENCE_ROLLBACK_COMPLETE/);
  assert.doesNotMatch(sql, /\nCOMMIT;|DELETE FROM runtime\.(orders|executions|runtime_events)|TRUNCATE/);
  assert.ok(sql.indexOf("INSERT INTO runtime.order_lifecycle_state") < sql.indexOf("INSERT INTO runtime.market_data_snapshots"));
});

test("source shape drift unexpected table names and missing lag fail closed", () => {
  const start = source.indexOf("override fun rebuildOrderLifecycleState()");
  const replacement = source.slice(0, start) + source.slice(start).replace("${names.executions}", "${names.unreviewedTable}");
  assert.throws(() => extractReferenceSql(replacement, "lifecycle", manifest), /unreviewed/);
  assert.throws(() => buildReferenceScript(source, { stage: "market" }, { ...manifest, sourceLag: undefined }));
  assert.throws(() => buildReferenceScript(source, { stage: "both" }, { ...manifest, sourceMax: 4222124650669353 }));
  // Restrict mutation to the reference method; other methods may have same binding.
  const marker = source.indexOf("override fun refreshMarketDataSnapshots(");
  const actualDrift = source.slice(0, marker) + source.slice(marker).replace("ps.setString(2, sourceProjectionName)", "ps.setString(2, projectionName)");
  assert.throws(() => extractReferenceSql(actualDrift, "market", manifest), /binding/);
});

test("untrusted projection names use hex literals and cannot inject SQL or psql commands", () => {
  const payload = "x'; COMMIT;\\! sh\n";
  const { sql } = buildReferenceScript(source, { stage: "market", projectionName: payload }, { ...manifest, sourceProjectionName: payload });
  assert.ok(!sql.includes(payload));
  assert.match(sql, /convert_from\(decode\('[a-f0-9]+', 'hex'\), 'UTF8'\)/);
});

test("verifier rejects missing rollback even with matching rows and retains semantic mismatches", () => {
  const good = {stage:"lifecycle",incrementalRows:"10",referenceRows:"10",missingFromReference:"0",extraInReference:"0",pass:true};
  const output = row => `REFERENCE_STARTED\n${JSON.stringify(row)}\nREFERENCE_ROLLBACK_COMPLETE\n`;
  assert.equal(verifyReferenceOutput(output(good), "lifecycle").pass, true);
  assert.throws(() => verifyReferenceOutput(output(good).replace("REFERENCE_ROLLBACK_COMPLETE", ""), "lifecycle"));
  assert.throws(() => verifyReferenceOutput(output({...good, missingFromReference:"1",pass:false}), "lifecycle"));
  assert.throws(() => verifyReferenceOutput(output(good), "both"));
  assert.throws(() => verifyReferenceOutput(output({...good,incrementalRows:"0",referenceRows:"0"}), "lifecycle"));
});
