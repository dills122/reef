# Performance Library Investigation Plan

## Purpose

Define the focused library and implementation spikes Reef should run before changing the runtime hot path or simulator load path.

This is not a blanket "replace libraries" plan. Every candidate must beat the current implementation under Reef workloads, preserve deterministic behavior, and avoid weakening API validation or persistence guarantees.

## Priority Summary

| Priority | Area | Language | Decision Target |
|---|---|---|---|
| 1 | Runtime DB write path | Kotlin/JVM + Postgres | choose safe batching/COPY strategy for the persistence sprint |
| 1 | Runtime JSON parser/serializer | Kotlin/JVM | choose JSON default for command DTOs, response DTOs, and JSONB payloads |
| 2 | Runtime HTTP boundary stack | Kotlin/JVM | decide whether JDK `HttpServer` should remain or be replaced/isolated |
| 2 | Runtime-engine transport | Kotlin/JVM + Go | keep gRPC/protobuf as primary transport and HTTP/JSON as fallback |
| 3 | Simulator and Go JSON | Go | prevent simulator/reporting/HTTP fallback from becoming benchmark bottlenecks |
| 3 | Compression/archive codecs | Go or Kotlin/JVM | choose archive/report codec defaults once EOD/export work starts |

## Task 1: Runtime Hot Path Library Investigation

### Scope

Areas:
- `services/platform-runtime`
- runtime boundary routes under `/api/v1`
- command capture, idempotency, runtime event, order, execution, and trade persistence
- JSONB payload generation and binding

Use cases:
- parse submit/cancel/modify command JSON
- serialize command results and query responses
- construct event, command-capture, and idempotency payloads without manual JSON string assembly
- batch hot-path writes with stable latency and predictable transaction boundaries

### Candidates

Kotlin JSON:
- current implementation baseline
- `kotlinx.serialization` as the default sane target
- DSL-JSON only as an ultra-hot DTO benchmark spike
- Jackson Blackbird only if Jackson ecosystem compatibility becomes valuable

DB write path:
- pgjdbc + HikariCP baseline
- prepared statement batches
- explicit multi-row insert statements
- pgjdbc `reWriteBatchedInserts`
- pgjdbc `CopyManager` / `COPY` for append-only bulk, archive, report, and replay-load paths

HTTP boundary:
- current JDK `HttpServer` baseline
- Ktor Netty benchmark
- Vert.x Web benchmark
- raw Netty only if Ktor/Vert.x cannot meet measured needs

Runtime-engine transport:
- gRPC/protobuf primary path
- HTTP/JSON fallback retained for migration and parity testing

### Acceptance Criteria

- Benchmarks include current baseline and at least one candidate per area.
- Results report accepted RPS, submitted RPS, p50/p95/p99, allocation rate where available, DB write latency, and top reject/error reasons.
- JSON benchmarks include malformed input and boundary validation behavior, not only happy-path serialization speed.
- DB benchmarks compare single-row writes, prepared batches, explicit multi-row inserts, and `reWriteBatchedInserts` on the same schema shape.
- `COPY` is evaluated only for append-only bulk/report/archive workflows, not ordinary command acknowledgment paths.
- Recommendation states integration risk, maintenance risk, and whether the candidate should be adopted now, deferred, or rejected.

### Initial Recommendation

Start the persistence sprint with pgjdbc + HikariCP + prepared batches and explicit multi-row insert benchmarks. Avoid R2DBC/reactive DB access for now; Reef's near-term problem is controlled durable write batching and predictable latency, not a large number of suspended DB calls.

Use `kotlinx.serialization` as the first typed JSON target. Treat DSL-JSON as a benchmark-only spike until the DTOs and validation path are stable enough to justify its integration cost.

## Task 2: Go Simulator And Engine Fallback Investigation

### Scope

Areas:
- `services/simulator`
- matching-engine HTTP adapter fallback
- simulator reports, telemetry, replay packs, and future archive artifacts

Use cases:
- parse JSON/YAML session configs
- generate large JSON reports and NDJSON telemetry
- run high-rate HTTP client traffic without simulator-side bottlenecks
- keep matching-engine HTTP fallback efficient enough for local comparison
- compress archive/report outputs

### Candidates

Go JSON:
- standard library `encoding/json` baseline
- `goccy/go-json`
- `sonic`
- `segmentio/encoding/json`
- `easyjson`

Go HTTP client:
- tuned `net/http`
- `fasthttp` benchmark only; do not assume it wins for Reef's traffic shape

Compression:
- `klauspost/compress` for gzip/zstd/archive-oriented work

### Acceptance Criteria

- Simulator benchmark proves client overhead is not limiting runtime capacity measurements.
- JSON candidates are checked for compatibility differences, malformed payload behavior, UTF-8 behavior, and allocation behavior.
- `fasthttp` is adopted only if it materially improves Reef load generation without breaking needed HTTP semantics.
- Compression candidates are evaluated on report/archive throughput and compression ratio, not command hot-path latency.

### Initial Recommendation

Keep Go library changes behind benchmark evidence. The simulator is important, but the runtime DB write path is the current higher-risk bottleneck. Go library swaps should happen only when stress diagnostics show simulator-side pressure or report/archive generation becomes expensive.

## Source Anchors

- kotlinx.serialization JSON: <https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/>
- DSL-JSON: <https://github.com/ngs-doo/dsl-json>
- pgjdbc CopyManager: <https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/copy/CopyManager.html>
- pgjdbc COPY API examples: <https://jdbc.postgresql.org/documentation/server-prepare/>
- goccy/go-json: <https://github.com/goccy/go-json>
- sonic: <https://github.com/bytedance/sonic>
- segmentio encoding/json: <https://github.com/segmentio/encoding>
- fasthttp: <https://github.com/valyala/fasthttp>
