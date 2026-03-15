# Reef Angular Steering

## Scope

Angular is the primary framework for Reef's operational platform UI.

## Product Role

The Angular application should support institutional-style surfaces such as:

- order entry and blotter views
- operations and exception workbenches
- simulation control room
- admin/reference-data screens
- audit timeline and event explorer views

The UI should feel operational and inspectable, not like a retail trading app.

## Design Rules

### Reflect workflow state clearly

Users should be able to understand:

- current status
- next required action
- upstream cause
- downstream consequences

This is more important than decorative polish.

### Prefer state clarity over widget density

Institutional UIs often get crowded quickly.
Optimize for scanability, queue handling, and timeline comprehension.

### Preserve auditability in the interface

Where practical, major screens should expose links to event history, related entities, and workflow IDs.

## Application Architecture

Prefer feature-oriented organization:

```text
apps/platform-ui/src/app/
  core/
  shared/
  features/
    orders/
    trades/
    settlement/
    exceptions/
    simulation/
    audit/
```

Guidance:

- `core/` holds app bootstrap, routing, auth/session plumbing, and shell concerns
- `shared/` holds reusable UI primitives and utilities
- `features/` holds domain-specific screens, state, and API clients

Do not collapse the app into a generic component soup.

## State and Data Flow

- keep API models separate from view models where the screen needs reshaping
- prefer explicit feature state over hidden cross-component mutation
- use real domain language in stores, routes, and component names
- model loading, empty, error, and stale states intentionally

## UX Guidance

- prioritize tables, queues, timelines, drill-down panels, and status-rich detail screens
- design for keyboard-heavy operator workflows where appropriate
- use visual hierarchy to show lifecycle progression and exception severity
- avoid over-animating operational screens

For early versions, one Angular app can host multiple surfaces so long as feature boundaries remain clear.

## Testing Guidance

Prioritize:

- feature-level UI behavior tests
- route and access-flow tests
- API integration tests with mocked platform responses
- critical workflow tests for order entry, trade inspection, and exception handling

## Avoid

- pushing business rules into components
- coupling screen logic directly to raw backend response shapes everywhere
- designing around toy demo flows that hide real workflow complexity
