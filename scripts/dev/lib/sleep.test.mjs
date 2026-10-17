import assert from "node:assert/strict";
import { test } from "node:test";
import { sleep } from "./sleep.mjs";
import { sleep as devUtilsSleep } from "./dev-utils.mjs";

test("sleep resolves after the delay", async () => {
  const started = Date.now();
  await sleep(20);
  assert.ok(Date.now() - started >= 15);
});

test("dev-utils re-exports the shared sleep", () => {
  assert.equal(devUtilsSleep, sleep);
});
