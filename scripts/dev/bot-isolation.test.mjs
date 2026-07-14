import assert from "node:assert/strict";
import {
  hostedBotContainerArgs,
  hostedBotContainerNetworks,
  hostedBotContainerReachableUrl,
  validateHostedBotContainerNetwork,
} from "./lib/bot-isolation.mjs";

for (const network of hostedBotContainerNetworks) {
  assert.equal(validateHostedBotContainerNetwork(network), network);
  const args = hostedBotContainerArgs({
    repoRoot: "/tmp",
    command: ["bun", "--version"],
    network,
  });
  assert.ok(args.includes(`--network=${network}`));
}

assert.throws(
  () => validateHostedBotContainerNetwork("venue-test-net"),
  /container network must be one of none, bridge, host; got venue-test-net/,
);
assert.throws(
  () => hostedBotContainerArgs({ repoRoot: "/tmp", command: ["bun", "--version"], network: "container:platform-runtime" }),
  /container network must be one of none, bridge, host; got container:platform-runtime/,
);

assert.equal(hostedBotContainerReachableUrl("http://127.0.0.1:8080"), "http://host.docker.internal:8080");
assert.equal(hostedBotContainerReachableUrl("http://localhost:8080/api/v1"), "http://host.docker.internal:8080/api/v1");
assert.equal(hostedBotContainerReachableUrl("http://example.test:8080"), "http://example.test:8080");
assert.equal(hostedBotContainerReachableUrl("not a url"), "not a url");

console.log("bot isolation checks passed");
