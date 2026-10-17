import assert from "node:assert/strict";
import { test } from "node:test";
import { parseKeyValueArgs } from "./cli-args.mjs";

test("parses space-separated, inline and bare flag forms", () => {
  assert.deepEqual(
    parseKeyValueArgs(["--report", "a.json", "--out=b.json", "--write-baseline", "--strict", "--last"]),
    { report: "a.json", out: "b.json", "write-baseline": "true", strict: "true", last: "true" },
  );
});

test("a flag followed by another option is bare, not its value", () => {
  assert.deepEqual(parseKeyValueArgs(["--a", "--b", "x"]), { a: "true", b: "x" });
});

test("empty argv yields empty object and positionals throw", () => {
  assert.deepEqual(parseKeyValueArgs([]), {});
  assert.throws(() => parseKeyValueArgs(["stray"]), /unexpected positional argument: stray/);
});
