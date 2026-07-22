# Contributing To Reef

Start with the canonical [developer onboarding guide](./docs/ONBOARDING.md).
It covers the clean-machine prerequisites, dependency bootstrap, local stack,
smoke test, module-specific toolchains, and the boundary between ordinary
development and hosted infrastructure access.

Before changing architecture, behavior, or a shared contract, read
[`AGENTS.md`](./AGENTS.md), the [project overview](./REEF_PROJECT_OVERVIEW.md),
the [technical design](./REEF_TECHNICAL_DESIGN.md), and the
[steering index](./docs/steering/README.md).

Use a feature branch. Keep changes bounded, add focused tests for behavior, and
update contracts and documentation in the same change when setup, commands,
workflows, or architecture direction move. A handoff should include the branch
name, PR title and summary, and the exact test evidence.
