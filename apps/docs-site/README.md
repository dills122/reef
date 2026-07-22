# Docs Site

Reef's Astro/Starlight public documentation site: project overview, Bot Arena/Bot SDK docs, trading API reference, and data schema reference.

Reef is pre-release and under heavy development — see the [Current Status](src/content/docs/overview/status.md) page for what's actually built.

## Local Development

From the repository root, use the helper that selects the checked-in
`.node-version` through `fnm` when it is available:

```bash
make docs-site-dev
```

For direct app commands, first make sure your active Node matches the repository
version:

```bash
cd apps/docs-site
npm ci
npm run dev
```

Build a static production bundle:

```bash
make docs-site-build
# or, with the correct Node active: npm run build
```

The docs site intentionally owns an npm lockfile. From the repository root,
`make dev-bootstrap` installs this dependency set along with the root and Arena
dependencies.

## Deployment

GitHub Pages deployment is handled by `.github/workflows/docs-site.yml` on pushes to `master`/`main` that touch `apps/docs-site/**` or the workflow file. The workflow builds this Astro/Starlight site with Node 22 and publishes `apps/docs-site/dist`.

The Pages build derives its default owner and repository from GitHub context:

```bash
DOCS_SITE_URL=https://<repository-owner>.github.io
DOCS_SITE_BASE=/<repository-name>
```

Set the GitHub Actions variables `DOCS_SITE_URL` and `DOCS_SITE_BASE` when a
custom domain or nonstandard Pages path is used. The current canonical values
resolve under `https://dills122.github.io/reef/`.

GitHub social and edit links derive from `GITHUB_REPOSITORY`. Set
`DOCS_SITE_REPOSITORY=<owner>/<repository>` and `DOCS_SITE_EDIT_BRANCH=<branch>`
to override those values for a fork, renamed repository, or nonstandard default
branch. The edit branch otherwise follows `GITHUB_REF_NAME` in CI and falls
back to `master` locally.

## Content

Pages live under `src/content/docs/`, organized as `overview/`, `arena/`, `api/`, `schema/`. Each page curates a short narrative and links back to the full source docs under `docs/` in the repo root — content here should not fork or duplicate those docs wholesale.

Build guidance: follow [`docs/steering/astro.md`](../../docs/steering/astro.md). Keep public documentation honest about current implementation status; do not market features the repository does not support yet.
