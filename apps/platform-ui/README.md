# Platform UI

This application will host Reef's Angular-based operational UI.

Initial deployment target:

- local developer control room backed by a local control API
- future hosted simulation environment only after a separate backend/platform lift
- GitHub Pages is reserved for project overview/docs, not for running simulations directly

V1 developer control room:

- home/control room overview
- simulate/run builder
- simulate/active run
- observe/run results
- observe/compare runs
- observe/trace explorer

Later platform surfaces:

- scenario catalog and builder
- run history
- environment health
- dev controls
- runtime config snapshot
- order entry and blotter views
- reference data administration
- trade and post-trade workflow views
- exception and operations workbench
- audit and event explorer

Simulator control room planning:

- [`docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](../../docs/archive/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)

Project pitch:

- [`docs/PROJECT_PITCH.md`](../../docs/archive/PROJECT_PITCH.md)

Build guidance:

- follow [`docs/steering/angular.md`](../../docs/steering/angular.md)
- preserve feature boundaries by domain, not by generic shared-page patterns
- optimize for inspectability and operator workflows
