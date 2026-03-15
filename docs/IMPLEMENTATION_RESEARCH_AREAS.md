# Reef Implementation Research Areas

## Purpose

This document identifies where external research is likely to improve implementation quality and where it is unnecessary because the design is already clear enough.

The goal is to avoid cargo-culting architecture from blog posts while still using outside material where it materially sharpens decisions.

## Research Not Needed Yet

These areas are already well defined enough to proceed without outside research:

- modular monolith plus separate engine as the initial deployment shape
- Kotlin runtime plus Go engine split
- Angular for the operator UI
- Astro for the docs site
- hybrid persistence model of current-state tables plus append-only events
- simulation using the same command paths as manual users

These should be implemented first and revisited only if concrete pain emerges.

## Research Worth Doing Soon

### 1. Kotlin runtime framework and serialization choices

Question:
what is the leanest Ktor-aligned stack for JSON APIs, testing, migrations, and modular application structure without pulling in unnecessary framework weight?

Why it matters:

- the runtime will become the orchestration center of the platform
- early choices here will affect testability and persistence patterns

Desired output:

- recommended Ktor stack
- JSON library choice
- migration tool choice
- testing stack choice

### 2. Kotlin-Go contract evolution path

Question:
when should Reef move from HTTP JSON hand-modeled contracts to protobuf, and what is the lowest-friction path to do that without excessive codegen churn?

Why it matters:

- the current HTTP JSON path is adequate for now
- the project design expects eventual protobuf-backed contracts

Desired output:

- migration trigger points
- package layout for schemas
- codegen workflow recommendation

### 3. Event log and read-model persistence pattern

Question:
what is the cleanest implementation shape for append-only event storage plus rebuildable read models inside a modular Kotlin service?

Why it matters:

- auditability and replay are core product goals
- poor early design here can make later simulation and timeline work painful

Desired output:

- recommended table shapes
- event metadata rules
- projection update pattern

### 4. Matching engine behavioral scope for v1

Question:
which order-handling features are essential to make the engine feel institutionally credible in a portfolio project, and which should wait?

Why it matters:

- it is easy to disappear into exchange-engine complexity
- a strong v1 needs realism without endless market-microstructure scope

Desired output:

- recommended v1 order feature set
- deferrable features list
- test scenarios that best demonstrate realism

### 5. Angular operator UI information architecture

Question:
what layout and state model best fit operator-style workflows for orders, trades, post-trade queues, and audit exploration?

Why it matters:

- the UI should feel operational, not like a generic dashboard or retail app

Desired output:

- shell and route recommendations
- core screens for Phase 1
- table/detail/timeline interaction model

## Research Worth Deferring

Do not spend time researching these yet:

- NATS deployment patterns
- advanced event sourcing literature
- exchange low-latency optimization
- production Kubernetes topologies
- real settlement-network integration patterns
- real exchange connectivity

Those are later-phase concerns and are not current bottlenecks.

## Recommended Research Order

If outside research is pursued, do it in this order:

1. Kotlin runtime stack and modular service structure
2. event log and read-model pattern
3. contract evolution path to protobuf
4. v1 matching engine feature scope
5. Angular operator UI information architecture

## Suggested Output Format For Future Research Notes

Each research topic should produce:

- decision statement
- alternatives considered
- recommended option
- why it fits Reef specifically
- what it unlocks next
- what remains deferred
