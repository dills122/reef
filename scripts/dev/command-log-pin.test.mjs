import test from "node:test";
import assert from "node:assert/strict";
import {
  buildDeletePinSql,
  buildListPinsSql,
  buildUpsertPinSql,
  normalizeSelectorType,
} from "./command-log-pin.mjs";

test("normalizes supported retention pin selector types", () => {
  assert.equal(normalizeSelectorType("idempotency_prefix"), "idempotency_prefix");
  assert.equal(normalizeSelectorType("command_id"), "command_id");
  assert.equal(normalizeSelectorType("run_id"), "run_id");
  assert.throws(() => normalizeSelectorType("payload"), /invalid retention pin selector type/);
});

test("builds upsert pin SQL with escaped values", () => {
  const sql = buildUpsertPinSql({
    pinId: "pin-1",
    selectorType: "idempotency_prefix",
    selectorValue: "intake-'abc",
    reason: "keep 'run'",
  });

  assert.match(sql, /INSERT INTO command_log\.retention_pins/);
  assert.match(sql, /ON CONFLICT \(selector_type, selector_value\) DO UPDATE/);
  assert.match(sql, /intake-''abc/);
  assert.match(sql, /keep ''run''/);
});

test("builds list and delete pin SQL", () => {
  assert.match(buildListPinsSql(), /FROM command_log\.retention_pins/);
  assert.match(
    buildDeletePinSql({ selectorType: "client_id", selectorValue: "client-1" }),
    /DELETE FROM command_log\.retention_pins/,
  );
});
