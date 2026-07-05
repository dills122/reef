# Docker Hub Image Publishing

The deployment stack expects these images by default:

- `dills122/reef-platform-runtime:latest`
- `dills122/reef-matching-engine:latest`
- `dills122/reef-simulator:latest`

`.github/workflows/container-images.yml` should publish those images to Docker
Hub. Use Docker Hub for public deployment images; keep image names overridable
with `REEF_PLATFORM_RUNTIME_IMAGE`, `REEF_MATCHING_ENGINE_IMAGE`, and
`REEF_SIMULATOR_IMAGE`.

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

The workflow should publish on pushes to `main`, version tags matching `v*`, and
manual `workflow_dispatch`.

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

From a machine that is not logged in to Docker Hub, public packages should pull
with:

```bash
docker pull dills122/reef-platform-runtime:latest
docker pull dills122/reef-matching-engine:latest
docker pull dills122/reef-simulator:latest
```
