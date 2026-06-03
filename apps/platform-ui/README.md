# Platform UI

This application will host Reef's Angular-based operational UI.

Planned surfaces:

- order entry and blotter views
- reference data administration
- trade and post-trade workflow views
- exception and operations workbench
- simulation control room
- audit and event explorer

Simulator control room planning:

- [`docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](../../docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)

Build guidance:

- follow [`docs/steering/angular.md`](../../docs/steering/angular.md)
- preserve feature boundaries by domain, not by generic shared-page patterns
- optimize for inspectability and operator workflows
