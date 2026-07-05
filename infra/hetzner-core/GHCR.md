# GHCR Image Publishing

The deployment stack expects these images by default:

- `ghcr.io/dills122/reef-platform-runtime:latest`
- `ghcr.io/dills122/reef-matching-engine:latest`
- `ghcr.io/dills122/reef-simulator:latest`

`.github/workflows/container-images.yml` publishes those images with
`GITHUB_TOKEN` and repository-scoped `packages: write` permission.

## Repository Settings

In GitHub, confirm:

- Actions are enabled for `dills122/reef`
- Workflow permissions allow package writes requested by workflow YAML
- Existing packages with the same names, if any, are connected to `dills122/reef`

The workflow publishes on pushes to `main`, version tags matching `v*`, and
manual `workflow_dispatch`.

## Package Visibility

New GHCR packages usually start private. For the simplest MVP deployment, make
these three packages public after the first successful publish:

1. Open `https://github.com/dills122?tab=packages`.
2. Open each Reef package.
3. Open package settings.
4. Change visibility to public.
5. Keep the package linked to `dills122/reef` and inheriting repository access.

If packages stay private, the server must authenticate to GHCR:

```bash
echo "$GHCR_READ_TOKEN" | docker login ghcr.io -u dills122 --password-stdin
```

Use a classic GitHub PAT with only `read:packages` for server pulls.

## Verification

From a machine that is not logged in to GHCR, public packages should pull with:

```bash
docker pull ghcr.io/dills122/reef-platform-runtime:latest
docker pull ghcr.io/dills122/reef-matching-engine:latest
docker pull ghcr.io/dills122/reef-simulator:latest
```

