# Reef

## Project Overview

Reef is a simulation-first institutional trading platform inspired by dark pool venue design and the broader post-trade lifecycle described in market infrastructure literature such as *Dark Pools* and *After the Trade Is Made*.

The goal is not to build a toy exchange demo. The goal is to build a realistic, production-shaped mock platform that models the lifecycle of privately matched trades from order intake through post-trade processing and settlement, while still being easy to run locally, inspect, replay, and demo.

Reef is intended to sit at the intersection of three ideas:

1. **Market venue simulator** — hidden liquidity, order intake, matching, executions, and venue monitoring.
2. **Post-trade operations platform** — allocations, confirmations, affirmations, settlement obligations, exception handling, and audit trails.
3. **Scenario-driven demo and research environment** — deterministic simulations, replayable runs, synthetic participants, accelerated time, and fault injection.

## Vision

Reef should feel like a real institutional market-infrastructure platform under the hood, while remaining approachable as a local development environment and portfolio-grade demo tool.

The project should be:

- **realistic in architecture**: clear bounded contexts, explicit workflows, auditability, structured contracts, and service seams that resemble real systems
- **simulation-first in usability**: scenario control, seeded runs, replay, pause/resume, inspectable event timelines, and resettable environments
- **forkable into something more serious**: the core should be production-shaped enough that a future fork could replace simulated components with more realistic integrations
- **educational and explainable**: every meaningful platform transition should be inspectable from input command to resulting event stream and state changes

## Product Goals

### Primary goals

- Model the lifecycle of institutional trades from order entry to settlement outcome.
- Provide a realistic but local-friendly architecture for learning market infrastructure concepts.
- Support both manual user operation and automated scenario-driven simulation.
- Serve as a strong technical portfolio project spanning frontend, backend, messaging, persistence, and engine design.

### Secondary goals

- Create a compelling demo environment for visualizing how execution and post-trade operations interact.
- Provide a foundation for future experimentation with matching logic, market microstructure, and operational workflows.
- Build a platform that can eventually host more advanced modules such as market data simulation, venue routing, or analytics.

### Non-goals for early versions

- Real exchange connectivity
- Real broker or custodian integrations
- High-frequency or ultra-low-latency optimization
- Regulatory completeness
- Production deployment for actual trading activity

## Core Concept

Reef is best understood as a **simulated institutional venue and post-trade platform** with a separate **simulation control plane**.

The platform has two major layers:

### 1. Core platform

The core platform owns real business behavior:

- order intake and validation
- hidden order book and matching requests
- trade creation and execution reporting
- trade enrichment and allocations
- confirmation and affirmation workflows
- settlement obligations and status transitions
- exception queues and repair actions
- audit trails and event history

This layer should behave like a real system and should not be polluted with demo-only shortcuts.

### 2. Simulation control plane

The simulation layer owns how activity is created and orchestrated:

- scenario definitions
- participant bots
- seeded randomness
- simulated market clock
- replay and reset behavior
- fault injection
- synthetic order and liquidity generation

This layer drives the platform through the same APIs and commands that manual users use.

## Operating Modes

Reef should support three complementary modes:

### Manual mode
Users operate the platform directly through the UI:
- configure participants and instruments
- submit orders
- review trades
- allocate and confirm trades
- manage exceptions

### Scenario mode
A predefined scenario drives the platform:
- deterministic participant behavior
- scripted market phases
- reproducible outcomes from a seed
- replayable event sequences

### Live simulation mode
Bots and synthetic actors create continuous activity:
- synthetic liquidity
- order waves
- market session activity
- operational failures and downstream workflows

## Users and Personas

Reef is not a retail trading app. The UX should reflect institutional roles.

### Trader
- submits and monitors orders
- reviews executions
- views blotter and venue activity

### Broker / sales trader
- manages routing and trade flow
- reviews allocations and client splits
- monitors fills and venue behavior

### Operations analyst
- handles confirmations and affirmations
- resolves breaks and mismatches
- works settlement and exception queues

### Compliance / oversight user
- inspects audit trails
- reviews event history
- monitors fairness and platform actions

### Admin / scenario operator
- configures scenarios and bots
- controls the market clock
- injects faults
- replays historical runs

## Product Surface Areas

### Execution side
- participant and account setup
- instrument reference data
- order submission
- hidden and conditional liquidity
- matching and execution reports
- venue monitor and live activity views

### Post-trade side
- trade enrichment
- allocations
- booking workflows
- confirmations and affirmations
- settlement obligations
- fails and breaks
- exception repair workbench

### Control and analysis side
- scenario management
- run orchestration
- replay tools
- event explorer
- system metrics and venue analytics

## High-Level Functional Scope

### v1 functional scope
- create participants, accounts, and instruments
- submit hidden buy/sell orders
- route orders into a matching engine
- produce executions and trades
- allocate trades to accounts
- generate confirmations
- move trades into settlement
- intentionally allow some trades to fail or break
- expose the full event trail in the UI

### later expansion areas
- conditional indications of interest
- firm-up workflows
- synthetic market data and price formation
- venue session rules and calendars
- netting and more advanced settlement logic
- participant strategy models
- advanced analytics and replay tooling

## Architectural Principles

### Production-shaped, simulation-friendly
The system should resemble a real platform internally, but be easy to run and demo locally.

### Modular first
Start with clear module boundaries and service seams, but avoid premature microservices.

### Core logic should be framework-light
Domain and workflow logic should not be deeply tied to a specific web framework.

### Events should explain the system
Every major state change should emit events that make the platform reconstructible and debuggable.

### Simulation should use the same contracts as users
Bots and scenario runners should invoke the same command/API paths as the UI.

### Every trade should be reconstructible
A trade should be traceable from initial order intent to final outcome through stored events and read models.

## Technology Direction

### Frontend
- **Angular** for platform UIs such as simulator, operations, admin, and audit consoles
- **Astro** for a static marketing/documentation site

### Backend and orchestration
- **Kotlin** for the main platform API/runtime and the simulator harness
- likely **Ktor** rather than Spring Boot for a lighter-weight, framework-thinner architecture

### Engine
- **Go** for the matching/execution engine
- chosen for strong performance, simple concurrency, and easier iteration than lower-level options

### Messaging and data
- **Postgres** for canonical relational state
- **NATS** as an eventual lightweight message backbone
- append-only **event log** for replay and audit
- likely **Protobuf** for shared cross-language contracts

## Why This Project Matters

Reef is valuable because it goes beyond a normal full-stack app. It exercises:

- domain-driven architecture
- event-driven workflow design
- messaging and distributed boundaries
- UI for operational systems
- multi-language service contracts
- realistic simulation control
- state machine modeling
- auditability and replay

It should become both a learning platform and a serious portfolio project.

## Initial Deliverables

The initial repository should eventually contain:

- a project overview and technical design
- a repository structure that supports Angular, Astro, Kotlin, and Go together
- shared protocol definitions
- a basic order-to-trade-to-settlement thin slice
- a scenario runner capable of deterministic seeded runs
- a UI that shows orders, trades, settlements, exceptions, and event timelines

## Short Elevator Pitch

**Reef is a simulation-first institutional trading venue and post-trade platform that models the lifecycle of hidden trades from order entry through settlement, using production-shaped architecture and inspectable event-driven workflows.**
