# Documentation audit — September 25, 2026

This is the evidence and rationale for the active/history consolidation. It is
not a new work board or a current product-status claim.

## Scope and method

- Inventoried tracked root guides, `docs/`, public Astro docs, service/package
  readmes, agent instructions, and local setup entry points: 130 tracked
  Markdown files under `docs/` before this pass, including 74 at its root.
- Compared local setup claims with `Makefile`, `.env.example`, layered Compose,
  `scripts/dev/lib/dev-stack.mjs`, and resolved default Compose services.
- Used `WORK_PLAN.md`, accepted decisions, steering, and the throughput ledger
  to separate live contracts/tasking from dated reports and completed plans.
- Preserved ongoing working-tree throughput edits and untracked `.planning/`
  handoffs. No performance baseline, failed run, decision, or contract was
  removed or promoted by this documentation pass.

## Findings and corrections

| Finding | Evidence | Correction |
| --- | --- | --- |
| Agents had a long unconditional canonical-doc reading list | Former `AGENTS.md` and steering index listed project design, performance, policy, and many topic docs together | Task-specific reading route in `AGENTS.md` and `docs/AI_CONTEXT.md`; steering grouped by owner area. |
| No single short answer for accepted local configuration and teardown | Setup instructions spread across README, `ONBOARDING.md`, 1,120-line `DEV_ENV.md`, and profile notes | `docs/LOCAL_CONFIGURATION.md` names default Compose, opt-in profiles, `dev-down`, destructive `dev-reset`, and authority; onboarding links it. |
| Reset documentation obscured smoke behavior | `scripts/dev/lib/dev-stack.mjs` defaults `DEV_RESET_RUN_SMOKE=0` and starts stack after volume removal/migration | Onboarding now says to run `make dev-smoke` after reset. |
| Public architecture page described default stack as one process and one Postgres | Resolved `make dev-compose-config ARGS="--services"` lists three Postgres, NATS, matching engine, API, four workers, four projectors | Public architecture and setup pages now describe actual default topology and link canonical local guide. |
| “Current” pages were last aligned September 4 | `CURRENT_STATUS.md` and `WORK_PLAN.md` both record that checkpoint; throughput ledger includes later scoped evidence | Dated banners and explicit source/evidence check before status claims. No September 4 claim silently rewritten. |
| Completed plans and old experiments sat beside current guidance | Phase 1/Arena separation, July architecture, run reports, and August research were at top level | 36 documents moved to `docs/archive/` or `docs/archive/research/`; 188 relative links repaired in the two move batches. |

After consolidation, `docs/` root has 52 Markdown files rather than 74, and
root prose has about 17,100 lines rather than 25,600. Remaining long files
include accepted decisions, active throughput evidence/plans, detailed
contracts, and opt-in advanced runbooks. Their length is not a reason to load
them for unrelated tasks.

## Intentional limits

- `WORK_PLAN.md` and `CURRENT_STATUS.md` still contain September 4 work and
  release statements. They are now explicitly dated; fresh reconciliation of
  later code, branches, and hosted runs needs its own evidence review.
- Long product/reference docs such as `BOT_ARENA_PLAN.md`, `DEV_ENV.md`, and
  `PROJECTION_THROUGHPUT_SCALING_PLAN.md` remain available for scoped work;
  they are excluded from routine first reads.
- External GitHub URLs that may have pointed to moved files cannot be
  automatically redirected by relative-link edits. In-repo relative links were
  repaired; archive index provides discovery.
- The docs-site build proves site rendering, not that every operational claim
  was re-run against a live host. This pass did not run hosted gates.

## Verification

- `make dev-compose-config ARGS="--services"` — passed; confirmed default
  service list and ordered `compose.base.yml,compose.local.yml` files.
- `npm --prefix apps/docs-site run build` — passed, 22 pages built.
- In-repo Markdown relative-link check — 656 root/docs links, zero missing.
- `git diff --check` — passed.
