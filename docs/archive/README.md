# Archive

Historical/superseded docs kept for evidence and record, not active guidance.

Archive movement is governed by [documentation lifecycle](../DOCUMENTATION_CLEANUP_PLAN.md).
Read [the active map](../README.md) before treating a historical result as
current. Files below retain their original scope, date, and failed attempts.

## September 2026 consolidation

- [Documentation audit](DOCUMENTATION_AUDIT_2026-09-25.md) — inventory,
  evidence, move rationale, and verification for this pass.
- `ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md` — July topology snapshot;
  current orientation starts at `docs/SYSTEM_OVERVIEW.md` and steering.
- `BOT_ARENA_AUTH_AND_PROVISIONING.md`, `BOT_ARENA_DO_SIMULATION_SOAK_CHECKLIST.md`,
  `BOT_ARENA_PHASE_1_READINESS_PLAN.md`, `BOT_ARENA_RUNNER_BENCH.md`, and
  `BOT_ARENA_SIMULATION_TUNING_SPRINT.md` — implementation and run history;
  current release gate lives in `docs/BOT_ARENA_RELEASE_READINESS.md`.
- `REEF_BOT_ARENA_SEPARATION_READINESS.md`, `REEF_BOT_ARENA_SEPARATION_SPRINT.md`,
  `REEF_BOT_ARENA_SEPARATION_PROMOTION.md` — pre-extraction checklist,
  implementation record, and promotion evidence. Accepted boundary remains
  D-053 and current code.
- `CODE_QUALITY_AUDIT_2026-07-13.md`,
  `IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md` — dated source/test audits;
  do not read their work status as current.
- `DEV_COMPOSE_OVERHAUL_PLAN.md` — completed local configuration foundation;
  use `docs/LOCAL_CONFIGURATION.md` and resolved Compose now.
- `DIGITALOCEAN_STRESS_TEST_PLAN.md`,
  `DURABLE_DIRECT_CRASH_GATE_RESULTS_2026-07-08.md`,
  `PERSISTENCE_MATERIALIZER_TEST_RESULTS_2026-07-04.md` — scoped remote/local
  run history; throughput authority remains `docs/THROUGHPUT_BASELINES.md`.
- `IMPLEMENTATION_RESEARCH_AREAS.md` — early research queue.
- `SYSTEM_INFRASTRUCTURE_BACKBONE.md`, `SYSTEM_BACKBONE_SERVICES.md`,
  `SYSTEM_BACKBONE_SIMULATOR_TOPOLOGY.md`, and
  `SYSTEM_SIMULATOR_ENVIRONMENT.md` — July long-form topology snapshots;
  current orientation starts at `docs/SYSTEM_OVERVIEW.md` and the relevant
  infrastructure runbook.
- `SIMULATION_TRAFFIC_PLAN.md`, `SIMULATOR_UPGRADE_BACKLOG.md` — shipped
  percentage-traffic history and candidate backlog; active tasking belongs in
  `docs/WORK_PLAN.md`.
- `CONTROL_ROOM_MONITORING_MVP.md` — initial local monitor decision and
  implementation scope, retained as history.
- `PERSISTENCE_HOT_PATH_CONFIGURATION.md`, `SLO_BASELINES.md` — historical
  profile knobs and early SLO targets; use current profiles and evidence ledger
  for run claims.
- `DOCUMENTATION_CLEANUP_PLAN_2026-09-04.md` — previous cleanup queue;
  current policy lives in `docs/DOCUMENTATION_CLEANUP_PLAN.md`.
- [`research/`](research/) — May/August research, spike, and review records;
  recent September work remains under `docs/research/` while active.

## Earlier archive

- dated stress/throughput baselines and abuse-breaker test evidence — superseded by later runs or folded into `docs/PERFORMANCE_LEARNINGS.md`
- `ROADMAP.md`, `PROJECT_PITCH.md`, `PROJECT_GOAL_PLAN_REVIEW.md` — superseded by `docs/CURRENT_STATUS.md`, `docs/WORK_PLAN.md`, and `REEF_PROJECT_OVERVIEW.md`
- `SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md` — stale sprint plan, superseded by the stream-ack execution path
- `ARCHITECTURE_FLOWS.md` — early HTTP-JSON/NATS-oriented diagrams, followed
  by the archived July `ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md` snapshot;
  current architecture starts at `docs/SYSTEM_OVERVIEW.md` and steering.
- `SPRINT_COMMUNICATION_API_ADMIN.md`, `SPRINT_POST_MATCH_ENGINES.md` — historical sprint plans, see `docs/WORK_PLAN.md`
- `SPRINT_CRITICAL_QUALITY_HARDENING.md` — historical hardening sprint, superseded by the current delivery policy and active work plan
- `ARCHITECTURE_THROUGHPUT_PLAN.md`, `ARCHITECTURE_THROUGHPUT_TRACKER.md`, `STREAM_ACK_ARCHITECTURE_PLAN.md`, `THROUGHPUT_SCALING_WORK_PLAN.md` — pre-D-041 throughput story and scaling targets; retained as evidence, while current hot-ingress direction lives in `docs/CURRENT_STATUS.md`, `docs/WORK_PLAN.md`, `docs/DECISIONS.md`, and `docs/PERFORMANCE_LEARNINGS.md`
- `personas-detailed.md` — early informal persona/session-config braindump, superseded by `docs/SIMULATOR_PERSONA_CONFIG.md`

Do not treat anything here as current direction. See `docs/steering/README.md` for canonical docs.
