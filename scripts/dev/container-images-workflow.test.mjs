import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const workflow = await readFile(
  new URL("../../.github/workflows/container-images.yml", import.meta.url),
  "utf8",
);

assert.match(workflow, /push: true/);
assert.match(
  workflow,
  /publish:\n[\s\S]*?permissions:\n      contents: read\n      packages: write\n[\s\S]*?strategy:/,
  "only the image publishing job should need GHCR package write access",
);
assert.match(
  workflow,
  /prerelease:\n[\s\S]*?permissions:\n      contents: write\n[\s\S]*?steps:/,
  "the pre-release job should retain release write access",
);
assert.match(
  workflow,
  /name: Log in to Docker Hub[\s\S]*username: \$\{\{ secrets\.DOCKERHUB_USERNAME \|\| env\.DOCKERHUB_NAMESPACE \}\}[\s\S]*password: \$\{\{ secrets\.DOCKERHUB_TOKEN \}\}/,
);
assert.match(
  workflow,
  /DOCKERHUB_NAMESPACE: \$\{\{ vars\.DOCKERHUB_NAMESPACE \|\| github\.repository_owner \}\}/,
  "the Docker Hub namespace should be configurable without editing the workflow",
);
assert.match(
  workflow,
  /name: Log in to GHCR[\s\S]*registry: ghcr\.io[\s\S]*password: \$\{\{ secrets\.GITHUB_TOKEN \}\}/,
);
assert.match(
  workflow,
  /images: \|\n            \$\{\{ env\.DOCKERHUB_NAMESPACE \}\}\/\$\{\{ matrix\.image \}\}\n            ghcr\.io\/\$\{\{ github\.repository_owner \}\}\/\$\{\{ matrix\.image \}\}/,
  "the same image build must be tagged for Docker Hub and GHCR",
);
assert.match(workflow, /cache-from: type=gha,scope=\$\{\{ matrix\.image \}\}/);
assert.match(
  workflow,
  /cache-to: type=gha,mode=max,ignore-error=true,scope=\$\{\{ matrix\.image \}\}/,
  "optional GitHub Actions cache export failures must not fail a successful image push",
);
assert.match(workflow, /prerelease:\n[\s\S]*needs: publish/);
assert.match(workflow, /Docker Hub tags:/);
assert.match(workflow, /GitHub Container Registry tags:/);

console.log("container image workflow guard checks passed");
