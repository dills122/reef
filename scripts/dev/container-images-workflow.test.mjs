import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const workflow = await readFile(
  new URL("../../.github/workflows/container-images.yml", import.meta.url),
  "utf8",
);

assert.match(workflow, /push: true/);
assert.match(workflow, /cache-from: type=gha,scope=\$\{\{ matrix\.image \}\}/);
assert.match(
  workflow,
  /cache-to: type=gha,mode=max,ignore-error=true,scope=\$\{\{ matrix\.image \}\}/,
  "optional GitHub Actions cache export failures must not fail a successful image push",
);
assert.match(workflow, /prerelease:\n[\s\S]*needs: publish/);

console.log("container image workflow guard checks passed");
