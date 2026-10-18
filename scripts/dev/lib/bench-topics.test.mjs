import assert from "node:assert/strict";
import { test } from "node:test";

import { benchTopicRpkCommands } from "./bench-topics.mjs";

test("benchmark topics are created with write.caching=false and a 4 MiB message cap, and altered when they exist", () => {
  const { create, alter } = benchTopicRpkCommands(["CMD", "EVT", ""], 16);
  assert.deepEqual(create, ["rpk", "topic", "create", "CMD", "EVT", "-p", "16", "-r", "1", "-c", "write.caching=false", "-c", "max.message.bytes=4194304"]);
  assert.equal(alter.length, 2);
  assert.deepEqual(alter[1], ["rpk", "topic", "alter-config", "EVT", "--set", "write.caching=false", "--set", "max.message.bytes=4194304"]);
});

test("benchmark topics reject empty input and bad partition counts", () => {
  assert.throws(() => benchTopicRpkCommands([], 16));
  assert.throws(() => benchTopicRpkCommands(["T"], 0));
});
