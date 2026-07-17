# Bot Arena Admin UI Follow-Ups

Status: follow-up backlog, July 2026.

Scope: `apps/arena-admin`, the hosted Bot Arena web app at
`reef-arena-admin.shrimpworks.dev`.

This document captures the next usability cleanup after splitting bot-owner
management from game/operator administration:

- `/bot-admin`: participant and bot-owner workflows.
- `/admin`: operator game/run workflows.
- public pages: arena overview, game types, leaderboard.

The goal is to keep the app useful as an operations tool without letting one
page grow into another dense "everything admin" surface.

## Product Shape

### Public Surface

Public users should be able to understand the game, inspect game types, and
read public leaderboard state without authentication.

Primary routes:

- `/`
- `/game-types`
- `/leaderboard`

Follow-ups:

- Rename `game types` to `games` if the shorter label reads better in the nav.
- Keep public leaderboard failures explicit: unavailable data is not the same
  as an empty scored run set.
- Add a last-updated timestamp to leaderboard data.

### My Bots

Participants and operators should manage only their own linked bots here.

Route:

- `/bot-admin`

Current responsibilities:

- show signed-in account and role/trust state
- list bots linked to the current Reef user
- show bot ownership and runtime config state
- open the runtime config modal
- save and clear bot config

Follow-ups:

- Rename the nav label from `bot admin` to `my bots`.
- Remove duplicate signed-in text where the header already shows it.
- Add copy buttons for bot id and secret path.
- Show latest bot version and submission/validation status when available.
- Show config `last updated`, `updated by`, stored keys, and missing required
  descriptor fields in the bot card.
- Add local JSON validation before submit, including object-only validation and
  clearer parse errors.
- Add a config-template action that inserts descriptor keys with placeholder
  values.
- Add an unsaved-changes guard before closing the config modal.
- Replace browser `confirm()` on destructive config clear with an in-app
  confirmation dialog.
- Keep all-bot/operator roster actions out of this route.

### Game Admin

Operators should use this surface for game operations and run inspection, not
bot-owner config management.

Route:

- `/admin`

Current responsibilities:

- list recent arena runs
- summarize run status, scenario, seed, coverage, winner, enforcement count
- link to run result and leaderboard data

Follow-ups:

- Rename the page title and nav consistently as `game admin`.
- Add a compact dashboard row:
  - recent runs
  - failed or disqualified runs
  - active game modes
  - last leaderboard update
- Add run filters:
  - mode
  - status
  - date window
  - bot id
- Add expandable run detail instead of forcing raw JSON links for common tasks.
- Show run duration, completed time, disqualified count, and enforcement
  reasons inline.
- Replace text links with compact action buttons:
  - `results`
  - `leaderboard`
  - `events`
- Add game-mode administration only when the API contract is ready.
- Keep participant bot config editing out of this route.

## Navigation And Session UX

Follow-ups:

- Rename top-level authenticated nav:
  - `bot admin` -> `my bots`
  - `admin` -> `game admin`
  - consider `game types` -> `games`
- Move `signed in as ...` into a compact account menu to reduce header width.
- Add account menu actions:
  - refresh session
  - sign out, once supported
  - show roles and trust state
- Add a small environment badge for hosted production-like usage.
- Keep role-based nav hiding:
  - public links for everyone
  - `my bots` for participants, reviewers, and operators
  - `game admin` for trusted operators/admins

## Error And Empty States

Follow-ups:

- Make fetch failures identify the failed capability:
  - session
  - owned bots
  - run list
  - leaderboard
  - config status
- Add useful next actions to empty states:
  - no linked bots -> link to submission docs or PR flow
  - no runs -> show expected ingest/run path
  - leaderboard unavailable -> show API route and retry action
- Use consistent inline error styling across public, bot, and game admin pages.
- Preserve the distinction between unavailable data and valid empty data.

## Operator Safety

Follow-ups:

- Separate read-only operator views from mutating controls.
- Require a reason field for every operator mutation.
- Show actor, correlation id, and timestamp after successful mutations.
- Add an audit panel for recent operator actions.
- Keep hosted raw internal routes invisible; UI must use `/admin/v1/...` or
  public `/api/v1/...` routes only.
- Add production-environment affordances before enabling any broader mutation
  controls.

## Component Cleanup

Follow-ups:

- Extract reusable page sections:
  - `AccountSummary`
  - `BotConfigModal`
  - `RunList`
  - `RunSummary`
  - `InlineError`
  - `EmptyState`
- Keep state loaders near routes until duplication is real, then move shared
  fetch state into small route-local modules.
- Keep cards for repeated items and modals only; avoid nested card layouts.
- Use stable dimensions for buttons, status chips, and modal content so loading
  or changing labels do not shift layout.

## Suggested PR Slices

### PR 1: Navigation And Account Polish

- Rename `bot admin` to `my bots`.
- Keep `game admin` label everywhere.
- Remove duplicate signed-in text from authenticated pages.
- Add a compact role/trust account strip or dropdown.

Acceptance:

- Header remains readable on laptop-width and mobile viewports.
- Participant sees public links plus `my bots`.
- Trusted operator sees public links, `my bots`, and `game admin`.

### PR 2: Bot Admin Usability

- Add copy bot id action.
- Improve config status summaries.
- Add local JSON parse validation.
- Add unsaved-changes warning.
- Replace clear-config browser confirm with a modal.

Acceptance:

- Config edit flow works without layout shift.
- Invalid JSON fails before network submit.
- Clearing config is visibly destructive and confirmable.

### PR 3: Game Admin Run Inspection

- Add dashboard summary row.
- Add run filters.
- Add expandable run detail.
- Replace raw links with action buttons.

Acceptance:

- Operator can inspect a run without leaving `/admin` for basic status,
  winner, results count, and enforcement reasons.
- Raw JSON links remain available for deeper debugging.

### PR 4: Audit And Safety

- Add recent audit events panel.
- Add reason/correlation fields to future mutation controls.
- Add hosted environment badge.

Acceptance:

- Operator mutations show actor, reason, and resulting audit context.
- Hosted environment is visually obvious before mutation.

## Open Questions

- Should `operator` and `arena-operator` remain separate role concepts in the
  UI, or should the session normalize them into one display label?
- Should game-mode administration live under `/admin`, or should it become
  `/admin/games` once mutation controls exist?
- Should bot config descriptors be required for every bot version before
  allowing config saves?
- Should trusted operators be allowed to manage another user's bot config from
  a separate emergency-only screen, or should all config writes stay owner-only
  except through CLI/DB repair?

## Current Guardrails

- Do not move public leaderboard reads under `/admin/v1/...`; they belong under
  the public `/api/v1/...` family.
- Do not expose raw `/internal/*` routes through the UI.
- Do not add mutation controls without audit context.
- Do not merge bot-owner workflows back into the game admin page.
