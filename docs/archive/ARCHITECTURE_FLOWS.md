# Reef Architecture and Flow Diagrams (Historical)

Superseded by [`ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md`](../ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md), which reflects the current Kafka/Redpanda direct-stream shape. These diagrams describe an earlier HTTP-JSON/NATS-oriented design and are kept only as historical context.

This document visualizes an earlier setup and intended future shape, including order flow, transport paths, and admin/public boundaries.

## Current System Shape

```mermaid
flowchart LR
  U["User / Operator"] --> SIM["Simulator CLI"]
  U --> RUNTIME["Platform Runtime (Kotlin)"]
  SIM --> RUNTIME
  RUNTIME -->|HTTP JSON| ENGINE["Matching Engine (Go)"]
  RUNTIME -->|In-memory or Postgres| STORE["Persistence Layer"]
  RUNTIME -->|Event/Trade APIs| OBS["Read APIs (/events, /trades, /orders)"]
```

## Current Order Flow (Implemented)

```mermaid
sequenceDiagram
  participant C as Client/Simulator
  participant R as Runtime API
  participant E as Matching Engine
  participant P as Runtime Persistence

  C->>R: SubmitOrder(commandId, traceId, ...)
  R->>R: Validate reference data + idempotency
  alt duplicate commandId
    R-->>C: prior result
  else new command
    R->>E: /orders/submit (HTTP JSON)
    E-->>R: accepted/rejected + executions/trades
    R->>P: persist order/executions/trades
    R->>P: append event envelope (trace/causation/sequence)
    R-->>C: structured response
  end
```

## Current Admin Surface (Direction)

```mermaid
flowchart LR
  ADMIN["Admin Operator"] --> CLI["Admin CLI (planned adapter)"]
  CLI --> APP["Runtime Admin Application Layer (planned)"]
  APP --> PERSIST["Persistence + Audit Events"]
```

## Target Future Shape

```mermaid
flowchart LR
  CLIENT["Client App / Integrator"] --> BOUNDARY["External API Boundary (/api/v1)"]
  OP["Internal Operator UI"] --> BOUNDARY
  BOUNDARY --> RUNTIME["Platform Runtime (Kotlin)"]
  RUNTIME -->|gRPC + Protobuf| ENGINE["Matching Engine (Go)"]
  RUNTIME --> DB["Postgres (Canonical State + Event Log)"]
  RUNTIME --> BUS["NATS (future async workflows)"]
  ADMIN["Admin CLI"] --> ADMINAPP["Admin Application Modules"]
  ADMINAPP --> RUNTIME
  NOTE["Future: Admin HTTP API reuses same admin modules"] -.-> ADMINAPP
```

## Target Order Flow (Public Boundary + gRPC)

```mermaid
sequenceDiagram
  participant X as External Client
  participant B as API Boundary (/api/v1)
  participant R as Runtime Application Layer
  participant E as Engine gRPC
  participant D as Postgres/Event Log

  X->>B: POST /api/v1/orders (auth token, idempotency key)
  B->>B: auth/rate-limit/validation
  B->>R: SubmitOrder command
  R->>R: idempotency + reference validation
  R->>E: SubmitOrder gRPC
  E-->>R: SubmitOrderResult
  R->>D: write state + append events
  R-->>B: structured outcome
  B-->>X: versioned API response
```

## Communication Layers

```mermaid
flowchart TB
  subgraph Public
    PUB["Public API Boundary (/api/v1, HTTP JSON)"]
  end

  subgraph Internal
    RT["Runtime"]
    EN["Engine"]
    ADM["Admin App Layer"]
  end

  subgraph Contracts
    PROTO["contracts/proto (versioned Protobuf)"]
  end

  PUB --> RT
  ADM --> RT
  RT -->|gRPC| EN
  RT -. uses .-> PROTO
  EN -. uses .-> PROTO
```
