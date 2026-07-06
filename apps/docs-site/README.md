# Docs Site

Reef's Astro/Starlight public documentation site: project overview, Bot Arena/Bot SDK docs, trading API reference, and data schema reference.

Reef is pre-release and under heavy development — see the [Current Status](src/content/docs/overview/status.md) page for what's actually built.

## Local Development

```bash
bun install
bun run dev
```

Or from the repo root:

```bash
make docs-site-dev
```

Build a static production bundle:

```bash
bun run build
# or: make docs-site-build
```

## Deployment

GitHub Pages deployment is handled by `.github/workflows/docs-site.yml` on pushes to `master`/`main` that touch `apps/docs-site/**` or the workflow file. The workflow builds this Astro/Starlight site with Node 22 and publishes `apps/docs-site/dist`.

The Pages build sets:

```bash
DOCS_SITE_URL=https://dills122.github.io
DOCS_SITE_BASE=/reef
```

Those values make project Pages URLs resolve under `https://dills122.github.io/reef/`.

## Content

Pages live under `src/content/docs/`, organized as `overview/`, `arena/`, `api/`, `schema/`. Each page curates a short narrative and links back to the full source docs under `docs/` in the repo root — content here should not fork or duplicate those docs wholesale.

Build guidance: follow [`docs/steering/astro.md`](../../docs/steering/astro.md). Keep public documentation honest about current implementation status; do not market features the repository does not support yet.
