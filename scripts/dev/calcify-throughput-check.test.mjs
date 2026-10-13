import test from "node:test";
import assert from "node:assert/strict";
import { compare, parseProperties, render } from "./calcify-throughput-check.mjs";

const baseline = { recorded: "t", warnFraction: 0.3, metrics: {
  rate: { value: 1000, better: "higher", unit: "/s" }, lat: { value: 100, better: "lower", unit: "ms" } } };

test("parses key=value lines and ignores noise", () => {
  assert.deepEqual(parseProperties("rate=1200\nbad line\nlat=80.5\n"), { rate: 1200, lat: 80.5 });
});

test("higher-is-better regression only when more than the threshold worse", () => {
  const rows = (rate) => compare({ rate, lat: 100 }, baseline).find((r) => r.key === "rate");
  assert.equal(rows(800).status, "ok");          // 20% worse: inside noise
  assert.equal(rows(600).status, "regression");  // 40% worse
  assert.equal(rows(1500).status, "improved");
});

test("lower-is-better direction is respected", () => {
  const rows = (lat) => compare({ rate: 1000, lat }, baseline).find((r) => r.key === "lat");
  assert.equal(rows(120).status, "ok");
  assert.equal(rows(150).status, "regression");
  assert.equal(rows(60).status, "improved");
});

test("a missing metric is reported, never silently passed", () => {
  const r = compare({ rate: 1000 }, baseline).find((x) => x.key === "lat");
  assert.equal(r.status, "missing");
  assert.match(render([r], baseline), /n\/a/);
});
