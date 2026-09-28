import assert from "node:assert/strict";
import test from "node:test";

import { assessDependencyGraph } from "./settlement-dependency-graph-check.mjs";

test("dependency graph computes exact longest retained admission chain", () => {
  assert.deepEqual(assessDependencyGraph([["1", ""], ["2", "1"], ["3", "1"],
    ["4", "2"], ["4", "3"]]),
  { admissions: 4, edges: 4, maxDepth: 3, deepestAdmissionRank: "4" });
});

test("dependency graph rejects missing or forward predecessor", () => {
  assert.throws(() => assessDependencyGraph([["1", ""], ["3", "2"]]), /missing or not earlier/);
  assert.throws(() => assessDependencyGraph([["1", "2"]]), /missing or not earlier/);
});

test("dependency graph rejects duplicate and inconsistent rows", () => {
  assert.throws(() => assessDependencyGraph([["1", ""], ["2", "1"], ["2", "1"]]), /duplicate or inconsistent/);
  assert.throws(() => assessDependencyGraph([["1", ""], ["1", ""]]), /duplicate or inconsistent/);
  assert.throws(() => assessDependencyGraph([["1", ""], ["2", ""], ["2", "1"]]), /duplicate or inconsistent/);
});
