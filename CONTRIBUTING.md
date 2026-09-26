# Contributing To Reef

Start with the [documentation map](./docs/README.md) and canonical
[developer onboarding guide](./docs/ONBOARDING.md).
It covers the clean-machine prerequisites, dependency bootstrap, local stack,
smoke test, module-specific toolchains, and the boundary between ordinary
development and hosted infrastructure access.

Before changing architecture, behavior, or a shared contract, read
[`AGENTS.md`](./AGENTS.md) and the relevant sections from the
[steering index](./docs/steering/README.md). Follow links to the
[project overview](./REEF_PROJECT_OVERVIEW.md) and
[technical design](./REEF_TECHNICAL_DESIGN.md) when the change affects those
boundaries.

Use a feature branch. Keep changes bounded, add focused tests for behavior, and
update contracts and documentation in the same change when setup, commands,
workflows, or architecture direction move. A handoff should include the branch
name, PR title and summary, and the exact test evidence.
