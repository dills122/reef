# Dual-Registry Image Publishing

The automatic container workflow publishes every deployment image to both
Docker Hub and GitHub Container Registry (GHCR). The deployment stack defaults
to these Docker Hub mirrors:

- `dills122/reef-platform-runtime:latest`
- `dills122/reef-matching-engine:latest`
- `dills122/reef-simulator:latest`

Equivalent GHCR mirrors use the `ghcr.io/dills122/` namespace. Keep image names
overridable with `REEF_PLATFORM_RUNTIME_IMAGE`, `REEF_MATCHING_ENGINE_IMAGE`,
and `REEF_SIMULATOR_IMAGE` so either registry can be selected without changing
Compose files.

## Docker Hub Setup

In Docker Hub:

- Create or confirm the `dills122` namespace.
- Create public repositories for each Reef image, or let the first push create
  them if the account permits it.
- Keep the repositories public so ephemeral workers can pull without registry
  login.

## GitHub Actions Secrets

Create a Docker Hub access token for GitHub Actions:

- Docker Hub account settings -> Personal access tokens.
- Access permissions: `Read & Write` is enough for publishing.
- Store it in GitHub Actions as `DOCKERHUB_TOKEN`.
- Store the Docker Hub username as `DOCKERHUB_USERNAME`.

The workflow can authenticate with:

```bash
echo "$DOCKERHUB_TOKEN" | docker login docker.io -u "$DOCKERHUB_USERNAME" --password-stdin
```

GHCR publishing uses the workflow-provided `GITHUB_TOKEN`; no additional GHCR
secret is required. The workflow requires `packages: write` permission. Confirm
that newly created GHCR packages are public if unauthenticated deployment pulls
are expected.

The workflow publishes on image-impacting pushes to `master`, version tags
matching `v*`, and manual `workflow_dispatch`. Push-triggered image sets also
create or update a GitHub pre-release marker, because the published deployment
images are intentionally unstable while platform contracts are still moving.
Publishing is one required operation: a failure to push either registry mirror
fails the image job instead of silently leaving the registries inconsistent.

## Private Image Fallbacks

Docker Hub Personal currently includes one private repository. That is fine for
a single private test image, but not enough if all Reef images need to stay
private.

For free private container images, GitLab's project container registry is the
best low-friction fallback. A private GitLab project gives private registry
visibility without moving the GitHub repository. For cloud-provider private
registries, Google Artifact Registry is reliable but only has a small storage
free tier and can charge for egress.

If packages are private, the server or worker must authenticate before pulls:

```bash
echo "$REGISTRY_READ_TOKEN" | docker login "$REGISTRY_HOST" -u "$REGISTRY_USER" --password-stdin
```

## Verification

From a machine that is not logged in to either registry, public packages should
pull from both mirrors:

```bash
docker pull dills122/reef-platform-runtime:latest
docker pull dills122/reef-matching-engine:latest
docker pull dills122/reef-simulator:latest
docker pull ghcr.io/dills122/reef-platform-runtime:latest
docker pull ghcr.io/dills122/reef-matching-engine:latest
docker pull ghcr.io/dills122/reef-simulator:latest
```
