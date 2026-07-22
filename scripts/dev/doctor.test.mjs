import assert from "node:assert/strict";

import { compareVersions, parseGoVersion, parseJavaMajor, repoRequirements } from "./doctor.mjs";

assert.equal(compareVersions("1.25.0", "1.24.9"), 1);
assert.equal(compareVersions("1.25", "1.25.0"), 0);
assert.equal(compareVersions("1.3.14", "1.3.15"), -1);
assert.equal(parseGoVersion("go version go1.25.0 darwin/arm64"), "1.25.0");
assert.equal(parseJavaMajor('openjdk version "21.0.8" 2025-07-15'), 21);

const requirements = repoRequirements();
assert.equal(requirements.bun, "1.3.14");
assert.equal(requirements.go, "1.25.0");
assert.equal(requirements.java, "21");
assert.equal(requirements.node, "22.22.1");

console.log("developer doctor tests ok");
