---
title: Developer Setup
description: Get a clean Reef checkout running and verified locally.
---

Reef has a Docker-first core workflow and a larger full-contributor toolchain.
The canonical, versioned source is the repository's
[developer onboarding guide](https://github.com/dills122/reef/blob/HEAD/docs/ONBOARDING.md);
use it for the complete prerequisites, work-area setup, troubleshooting, and
optional infrastructure boundaries.

## Core Local Stack

Install Git, Make, curl, Docker with Compose v2, and the Bun version pinned by
the root `package.json`. Then, from a clean checkout:

```bash
cp .env.example .env
make dev-doctor
make dev-up
make dev-smoke
```

The default local environment needs no cloud token, hosted database
credential, Tailscale account, or direct login to the hosted Reef box.

## Full Contributor Setup

Developers changing the native services or apps also need the Node version in
`.node-version` with npm, Go 1.25 or newer, and Java 21. Install the repository, Arena, and docs-site
dependency roots, then check the complete toolchain:

```bash
make dev-bootstrap
make dev-doctor ARGS=--full
```

Use focused verification for the area you change:

```bash
make test-go
make test-simulator
make test-platform-runtime
make test-bot-sdk
make check-proto-additive
```

## Optional Products And Infrastructure

Arena is an explicit overlay (`make dev-up-arena`); it is not required for core
Reef. Local Kubernetes, the Hetzner backbone, and disposable DigitalOcean
benchmark workers have separate runbooks and access requirements under
`infra/`. Hosted credentials and private Tailscale access are operator-only,
not general developer onboarding.

Read [CONTRIBUTING.md](https://github.com/dills122/reef/blob/HEAD/CONTRIBUTING.md)
before preparing a change, and use the full
[onboarding guide](https://github.com/dills122/reef/blob/HEAD/docs/ONBOARDING.md)
when a first-run check fails.
