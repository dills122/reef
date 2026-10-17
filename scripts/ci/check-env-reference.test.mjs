import assert from "node:assert/strict";
import test from "node:test";
import { compare, composeVariables, documentedVariables, parseBaseline } from "./check-env-reference.mjs";

test("composeVariables finds braced references with and without defaults", () => {
  const names = composeVariables("a: ${FOO}\nb: ${BAR:-x}\nc: ${BAZ-y}\nd: ${QUX:?required}\ne: $${ESCAPED}\nf: ${lower}");
  assert.deepEqual([...names].sort(), ["BAR", "BAZ", "FOO", "QUX", "lower"].sort());
});

test("documentedVariables accepts active and commented assignments", () => {
  const names = documentedVariables("# comment\nREEF_ENV=local\n# OPTIONAL_VAR=1\n  INDENTED=2\nnot a var\n");
  assert.deepEqual([...names].sort(), ["INDENTED", "OPTIONAL_VAR", "REEF_ENV"]);
});

test("compare flags new undocumented and stale baseline entries", () => {
  const result = compare({
    referenced: new Set(["A", "B", "C"]),
    documented: new Set(["A", "C"]),
    baseline: parseBaseline("# header\nB\nGONE\nC\n"),
  });
  assert.deepEqual(result.undocumented, ["B"]);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.stale, ["C", "GONE"]);
  const fresh = compare({ referenced: new Set(["B", "D"]), documented: new Set(), baseline: new Set(["B"]) });
  assert.deepEqual(fresh.added, ["D"]);
});
