import assert from "node:assert/strict";
import {
  hostedBotContainerArgs,
  hostedBotContainerNetworks,
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

console.log("bot isolation checks passed");
