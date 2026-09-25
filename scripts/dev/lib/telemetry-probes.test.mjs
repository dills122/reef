import assert from "node:assert/strict";
import http from "node:http";

import { requestHttpProbe, runProbesConcurrently } from "./telemetry-probes.mjs";

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

const server = http.createServer((request, response) => {
  if (request.url === "/ok") {
    response.setHeader("content-type", "application/json");
    response.end('{"ready":true}');
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
try {
  const ok = await requestHttpProbe({
    name: "ok",
    url: `http://127.0.0.1:${address.port}/ok`,
    captureJson: true,
    timeoutMs: 1000,
  });
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.json, { ready: true });

  const timedOut = await requestHttpProbe({
    name: "hang",
    url: `http://127.0.0.1:${address.port}/hang`,
    timeoutMs: 50,
  });
  assert.equal(timedOut.ok, false);
  assert.match(timedOut.error, /total timeout/);
} finally {
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
}
