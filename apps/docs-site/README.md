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

## Content

Pages live under `src/content/docs/`, organized as `overview/`, `arena/`, `api/`, `schema/`. Each page curates a short narrative and links back to the full source docs under `docs/` in the repo root — content here should not fork or duplicate those docs wholesale.

Build guidance: follow [`docs/steering/astro.md`](../../docs/steering/astro.md). Keep public documentation honest about current implementation status; do not market features the repository does not support yet.
