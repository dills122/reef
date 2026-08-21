import assert from "node:assert/strict";

import { runProbesConcurrently } from "./telemetry-probes.mjs";

const started = [];
const resolvers = [];
const probes = [{ name: "one" }, { name: "two" }, { name: "three" }];
const pending = runProbesConcurrently(probes, (probe) => {
  started.push(probe.name);
  return new Promise((resolve) => resolvers.push(() => resolve({ name: probe.name, ok: true })));
});

assert.deepEqual(started, ["one", "two", "three"]);
resolvers.forEach((resolve) => resolve());
assert.deepEqual(await pending, [
  { name: "one", ok: true },
  { name: "two", ok: true },
  { name: "three", ok: true },
]);
