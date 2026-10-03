import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRunDetailLoader } from "../../apps/arena-admin/src/lib/admin-run-detail.ts";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

function harness() {
  const requests = [];
  const publications = [];
  const api = {
    fetchAdminRuns(limit) {
      assert.equal(limit, 100);
      const request = { runs: deferred(), results: deferred(), events: deferred() };
      requests.push(request);
      return request.runs.promise;
    },
    fetchAdminRunResults(runId) {
      requests.at(-1).runId = runId;
      return requests.at(-1).results.promise;
    },
    fetchAdminRunEnforcementEvents(runId) {
      assert.equal(runId, requests.at(-1).runId);
      return requests.at(-1).events.promise;
    },
  };
  return { requests, publications, load: createRunDetailLoader(api, (state) => publications.push(state)) };
}

async function succeed(request) {
  request.runs.resolve([{ runId: "unrelated" }, { runId: request.runId }]);
  request.results.resolve([{ botId: `${request.runId}-bot` }]);
  request.events.resolve([{ reason: `${request.runId}-event` }]);
  await flush();
}

async function fail(request, error = new Error("old request failed")) {
  request.results.reject(error);
  request.runs.resolve([]);
  request.events.resolve([]);
  await flush();
}

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

const empty = { run: null, results: [], enforcementEvents: [], loading: false, error: "" };
function assertRun(state, runId) {
  assert.deepEqual(state, {
    run: { runId }, results: [{ botId: `${runId}-bot` }],
    enforcementEvents: [{ reason: `${runId}-event` }], loading: false, error: "",
  });
}

for (const order of [[0, 1], [1, 0]]) {
  const h = harness();
  h.load("A");
  h.load("B");
  assert.deepEqual(h.publications.at(-1), { ...empty, loading: true });
  await succeed(h.requests[order[0]]);
  if (order[0] === 0) {
    assert.equal(h.publications.length, 2, "old success must not finish current loading");
    assert.equal(h.publications.at(-1).loading, true);
  } else {
    assertRun(h.publications.at(-1), "B");
  }
  await succeed(h.requests[order[1]]);
  assert.equal(h.publications.length, 3, "only current request publishes completion");
  assertRun(h.publications.at(-1), "B");
}

for (const oldErrorFirst of [true, false]) {
  const h = harness();
  h.load("A");
  h.load("B");
  if (oldErrorFirst) {
    await fail(h.requests[0]);
    assert.equal(h.publications.length, 2);
    assert.equal(h.publications.at(-1).loading, true);
    await succeed(h.requests[1]);
  } else {
    await succeed(h.requests[1]);
    await fail(h.requests[0]);
  }
  assertRun(h.publications.at(-1), "B");
  assert.equal(h.publications.length, 3, "old error cannot overwrite data or loading");
}

for (const settle of [succeed, fail]) {
  const h = harness();
  h.load("A");
  h.load("");
  assert.deepEqual(h.publications.at(-1), empty);
  assert.equal(h.requests.length, 1, "empty selection must not fetch");
  await settle(h.requests[0]);
  assert.equal(h.publications.length, 2, "empty selection invalidates pending requests");
  assert.deepEqual(h.publications.at(-1), empty);
}

for (const settle of [succeed, fail]) {
  const h = harness();
  const cleanup = h.load("A");
  cleanup();
  await settle(h.requests[0]);
  assert.equal(h.publications.length, 1, "unmounted page must receive no completion");
}

{
  const h = harness();
  const cleanupA = h.load("A");
  h.load("B");
  cleanupA();
  await succeed(h.requests[1]);
  assertRun(h.publications.at(-1), "B");
  h.load("A");
  await succeed(h.requests[0]);
  assert.equal(h.publications.at(-1).loading, true, "same run ID cannot revive older request");
  await succeed(h.requests[2]);
  assertRun(h.publications.at(-1), "A");
}

for (const [error, message] of [[new Error("current failed"), "current failed"], [null, "run detail load failed"]]) {
  const h = harness();
  h.load("A");
  await fail(h.requests[0], error);
  assert.deepEqual(h.publications.at(-1), { ...empty, error: message });
  h.load("B");
  assert.deepEqual(h.publications.at(-1), { ...empty, loading: true });
  h.requests[1].runs.resolve([]);
  h.requests[1].results.resolve([]);
  h.requests[1].events.resolve([]);
  await flush();
  assert.deepEqual(h.publications.at(-1), empty, "missing metadata preserves empty state");
}

// Verify the route uses this tested loader and returns cleanup to Svelte's effect.
const page = readFileSync(new URL("../../apps/arena-admin/src/routes/admin/run/+page.svelte", import.meta.url), "utf8");
assert.match(page, /import \{ createRunDetailLoader \} from '\$lib\/admin-run-detail'/);
assert.match(page, /const loadRun = createRunDetailLoader\(/);
assert.match(page, /\$effect\(\(\) => loadRun\(runId\)\)/);
for (const field of ["run", "results", "enforcementEvents", "loading", "error"]) {
  assert.match(page, new RegExp(`${field} = state\\.${field};`));
}
console.log("arena admin run detail race and lifecycle checks passed");
