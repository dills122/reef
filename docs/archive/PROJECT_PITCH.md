# Reef Project Pitch

## Elevator Pitch

Reef is a local-first market simulation and trading-infrastructure playground.

It lets developers model realistic trading actors, run high-throughput order-flow simulations, inspect system behavior under load, and trace orders through the full lifecycle from API boundary to matching engine, persistence, events, and post-trade workflows.

The project is both an engineering testbed and a learning tool. Users can stress-test architecture decisions, experiment with throughput and backpressure, visualize market activity, and understand how exchange-style systems handle correctness, latency, failures, and scale.

## Product Framing

Reef is not only a matching engine demo. It is a production-shaped local platform for understanding how trading systems behave under real operational pressure.

Primary value:
- simulate realistic market participants and order flow
- observe throughput, latency, backpressure, rejects, and trace integrity
- compare runs and detect regressions
- explore order/trade lifecycle behavior end to end
- build intuition for market infrastructure, post-trade workflows, and operational controls

## Initial Audience

V1 audience:
- project developers
- engineers testing local changes
- QA-style validation of simulator/runtime behavior

Later audience:
- developers evaluating or experimenting with the project
- finance/market-structure learners
- people who want to run and visualize market simulations without deep system setup knowledge

## Platform UI Pitch

The planned Platform Control Room turns Reef from a CLI-only simulator into a visual developer cockpit.

It should let users:
- configure and launch simulations
- monitor live throughput, latency, failures, and backpressure
- inspect traces and order lifecycle events
- compare runs against baselines
- operate the local dev environment safely
- preserve reproducible commands and artifacts

Near term, this is a local developer tool. Longer term, the same UI concepts can support a fully hosted simulation environment for public demos, teaching, and experimentation.

## Hosting Framing

GitHub Pages is appropriate for:
- project overview
- documentation
- architecture notes
- scenario examples
- public learning material

GitHub Pages is not the simulation execution environment.

Running simulations requires:
- a local control API in developer mode, or
- a future fully hosted Reef environment with backend services, workers, storage, auth, safety controls, and quotas.
