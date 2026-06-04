# Reef Angular and Frontend Steering

## Scope

This document applies to Reef's Angular operational UI under `apps/platform-ui`
and any UI-facing shared models under `packages/ui-models`.

Use this alongside:

- [`architecture.md`](./architecture.md) for platform boundaries and domain shape
- [`external-api-boundary.md`](./external-api-boundary.md) for API contracts
- [`data-platform.md`](./data-platform.md) for source-of-truth and retention rules
- [`../SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](../SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md) for the first control-room buildout

Angular is the primary framework for Reef's internal platform UI. The UI is not
the system of record and must not become the place where domain rules live.

## Product Role

The Angular application should support institutional-style operational surfaces:

- simulation control room
- scenario run builder and active-run monitor
- run results, comparison, and trace exploration
- order entry and blotter views
- trade, settlement, and post-trade workflow views
- exception workbenches
- admin and reference-data screens
- audit timeline and event explorer views

The UI should feel operational, inspectable, and reliable. Prefer dense but
ordered workflows over marketing-style pages, decorative dashboards, or retail
trading-app patterns.

## Non-Negotiables

- Organize code by feature/domain, not by generic file type.
- Use standalone Angular APIs; do not introduce NgModule-based feature
  architecture.
- Keep components thin. Put orchestration in feature stores/services and domain
  decisions in backend/domain code.
- Keep API DTOs, command models, and screen view models separate when shapes
  diverge.
- Model loading, empty, error, stale, paused, replaying, and partial-data states
  explicitly.
- Preserve auditability in the UI by exposing workflow IDs, correlation IDs,
  timestamps, event links, and related entities where practical.
- Keep the local developer control room deterministic and resettable. Avoid
  hidden client-only state that changes simulation outcomes.
- Validate and sanitize all external inputs at the UI boundary, including API
  responses, imported scenario config, URL params, and local storage.
- Maintain accessible keyboard-first workflows for operational screens.
- Keep secrets, credentials, and sensitive payloads out of frontend bundles,
  logs, screenshots, and telemetry.

## Stack Defaults

- Use Angular 17+ patterns by default, and prefer the current Angular CLI version
  selected by the project when the app is scaffolded.
- Use strict TypeScript. Avoid `any`; if it is unavoidable, document why at the
  call site.
- Prefer standalone components, directives, pipes, route definitions, and
  providers.
- Use Signals for local UI state, derived values, and feature-store state where
  it keeps flows simpler.
- Use RxJS for HTTP streams, polling, cancellation, websockets/server-sent
  events, and other async workflows that benefit from stream composition.
- Use `inject()` where it improves locality and readability.
- Use modern Angular template control flow: `@if`, `@for`, and `@switch`.
- Use `DestroyRef` and `takeUntilDestroyed` for lifecycle-safe subscriptions.
- Prefer Angular CDK primitives for accessibility, overlays, focus management,
  tables, menus, portals, and scrolling.
- If Angular Material, Tailwind, or another UI system is added, adopt it
  intentionally and consistently. Do not mix unrelated component systems by
  accident.
- Reuse Reef design tokens from `extracted_assets/ready/theme.tokens.css` where
  practical instead of inventing one-off colors and spacing.

## Application Architecture

Default app shape:

```text
apps/platform-ui/src/app/
  core/
    api/
    config/
    diagnostics/
    layout/
    routing/
    session/
  shared/
    a11y/
    components/
    pipes/
    testing/
    utils/
  features/
    simulation-control/
    run-observation/
    audit-explorer/
    orders-and-execution/
    trade-processing/
    settlement/
    exceptions-and-operations/
    reference-data/
```

Guidance:

- `core/` holds app bootstrap, route guards, API base plumbing, session/auth
  adapters, diagnostics, and shell-level layout.
- `shared/` holds reusable UI primitives and framework-level utilities. Shared
  code must be domain-neutral.
- `features/` holds domain-specific routes, screens, data access, state, and
  view models.
- Feature folders should use Reef's bounded-context language. Prefer
  `exceptions-and-operations` over vague names like `workflow`.
- Keep feature routes lazy-loaded by default.
- Avoid cross-feature imports except through explicit shared contracts,
  `core/`, or `packages/ui-models`.

Recommended feature shape:

```text
features/simulation-control/
  simulation-control.routes.ts
  pages/
  components/
  data-access/
  state/
  models/
  testing/
```

Do not create a large generic `components/` inventory at the app root. Promote a
component to `shared/` only after at least two real feature consumers need the
same abstraction.

## Data and API Boundaries

- Centralize HTTP/API calls in `data-access/` services. Components should not
  call `HttpClient` directly.
- Treat backend contracts as external data. Decode, validate, and map them
  before using them in screens.
- Prefer explicit DTO-to-view-model mappers for operational screens with
  reshaped status, timeline, or aggregate data.
- Keep command payload builders close to the feature store/service that owns the
  workflow.
- Do not let UI-specific projections leak back into backend write-model design.
- Preserve platform metadata in view models when it helps operators inspect a
  flow: actor identity, seed, scenario ID, run ID, correlation ID, causation ID,
  event sequence, and server timestamp.
- For long-running operations, centralize polling, retry, cancellation, and
  stale-data detection in stores/services.
- Make optimistic updates rare. Use them only when the domain allows clear
  reconciliation and rollback.

## State Management

- Prefer Signals for component-local and feature-local state.
- Use feature stores/services for route-level workflows and multi-component
  screens.
- Introduce global state only for genuinely cross-cutting concerns such as
  session, environment selection, feature flags, or app diagnostics.
- Do not add NgRx Store by default. Add it only when cross-feature state,
  time-travel/debugging, or ecosystem tooling clearly pays for the complexity.
- Keep UI state, server/cache state, command state, and domain state separated.
- Persist client state only when it improves continuity and cannot affect
  deterministic simulation results.
- Keep local storage and IndexedDB schemas versioned if they store anything more
  than small preferences.

## UI and Interaction Guidance

- Prioritize tables, queues, timelines, split panes, detail drawers, filters,
  diff views, and trace explorers.
- Make workflow state obvious: current status, next action, upstream cause,
  downstream consequence, owner, age, and severity.
- Use domain-specific labels in navigation, route names, component names, and
  visible text.
- Prefer stable layouts with fixed column behavior, predictable resizing, and no
  unexpected reflow during updates.
- Design for keyboard-heavy operators: visible focus, logical tab order,
  shortcut-safe controls, and actions that do not require pointer precision.
- Use color as a secondary cue. Pair severity/status colors with text, icons, or
  shape.
- Avoid over-animation. Movement should clarify state transitions, not decorate
  the screen.
- Keep dashboards inspectable. Every aggregate should have a path to the
  underlying runs, events, orders, trades, exceptions, or logs.
- Do not build a marketing landing page inside `platform-ui`. The first screen
  should be the usable operational surface.

## Accessibility

Target WCAG 2.2 AA for the platform UI.

- Use semantic landmarks, headings, buttons, links, forms, and tables before
  custom ARIA.
- All interactive controls must be keyboard-operable.
- Focus indicators must be visible and high-contrast.
- Forms need programmatic labels, validation messages, and error associations.
- Async status changes that matter to task completion should be announced with
  Angular CDK `LiveAnnouncer` or equivalent.
- Modals, drawers, menus, and popovers must trap, restore, and order focus
  correctly.
- Data tables and grids need clear labels, sorting/filtering semantics, and
  accessible empty/error states.
- If a custom grid is introduced, verify keyboard navigation and screen-reader
  behavior before expanding its usage.

## Performance

- Lazy-load route-level features.
- Use `@for` tracking keys for repeated rows and event lists.
- Paginate, virtualize, or server-filter large result sets and traces.
- Move heavy client-side transforms, diffing, and replay visualization work off
  the main UI path when data size justifies it.
- Avoid expensive template functions and repeated derived calculations. Use
  `computed()` or memoized mapping where appropriate.
- Keep bundle additions intentional. Do not add charting, grid, or date/time
  libraries without a clear screen-level need.
- Display freshness and lag for live or polled operational data.

## Testing and Quality Gates

Prioritize tests around real operational behavior:

- mapper tests for API DTO to view model conversion
- feature-store tests for long-running state transitions
- component tests for filters, tables, forms, empty/error states, and action
  enablement
- route tests for access flow and deep-link behavior
- contract/fixture tests against mocked platform-runtime responses
- E2E tests for scenario start, active-run monitoring, result inspection, and
  trace exploration
- accessibility checks for key routes and workflows

Preferred defaults:

- Use the Angular test runner selected by the scaffold, but prefer Jest/Vitest
  plus Angular Testing Library when setting up new infrastructure.
- Use Playwright for E2E when browser workflows become meaningful.
- Test user-visible behavior rather than private component implementation.
- Every new workflow feature needs success, failure, empty, and loading coverage
  unless the gap is documented.
- Run the relevant app build, lint, typecheck, and tests before merge once those
  commands exist.

## Security and Privacy

- Do not trust client-side validation. Mirror validation in backend/domain
  boundaries.
- Do not use `innerHTML` or bypass Angular sanitization unless there is a
  reviewed, documented reason.
- Redact credentials, tokens, personal data, and large payloads from logs and
  diagnostics.
- Avoid storing tokens in local storage unless the authentication design
  explicitly accepts that risk.
- Treat scenario files and imported config as untrusted input.
- Keep external links safe with appropriate `rel` attributes when opening new
  contexts.
- Do not expose backend internal topology or secrets through environment files
  shipped to the browser.

## Avoid

- Business rules embedded in components or templates.
- Direct `HttpClient` calls from components.
- Raw backend response shapes spread across templates.
- Hidden cross-component mutation.
- Global state introduced for single-feature convenience.
- Generic `utils` and `shared` folders full of unrelated behavior.
- Mock-only demo flows that hide workflow IDs, error states, or audit history.
- Decorative UI that makes operational state harder to inspect.
