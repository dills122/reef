# Performance Code Audit (2026-05-23)

## Scope

Targeted hot-path audit and refactor across:
- matching engine core order-book path
- platform runtime order lifecycle event path

## Findings Addressed

1. Matching engine insertion path had repeated `sync.Map` lookups inside each book scan (`insertBuy` / `insertSell`), causing avoidable overhead per submit/modify.
2. Matching engine matching path repeatedly formatted identical numeric strings per match event.
3. Matching engine validation/parsing logic duplicated positive integer parsing and rejection handling.
4. Runtime order service duplicated lifecycle event assembly logic and allocated dynamic event lists without capacity hints.

## Refactors Implemented

### Matching Engine

Files:
- `services/matching-engine/internal/app/service.go`
- `services/matching-engine/internal/app/service_test.go`

Changes:
- Added `LimitPrice` to `restingOrder` so insertion can compare directly without map lookups.
- Replaced linear compare+map-load insertion logic with binary-search insertion (`sort.Search`) while preserving price-time ordering.
- Added generic helper `insertAt[T any]` for reusable ordered insertion mechanics.
- Added `parsePositiveInt` helper to reduce parse/validation duplication.
- Reused parsed match quantities/prices as strings in `appendMatch` to avoid repeated conversions.
- Added tests for buy/sell price-time insertion and parse helper.

### Platform Runtime

File:
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt`

Changes:
- Added shared `lifecycleEvent(...)` builder to remove repetitive event construction.
- Added `traceId(traceId, orderId)` helper to centralize trace fallback behavior.
- Pre-sized accepted lifecycle event list with `ArrayList` capacity (`1 + executions + trades`) to reduce reallocations.

## Validation

- `go test ./...` in `services/matching-engine` passed.
- `go test -race ./internal/app` in `services/matching-engine` passed.
- `./gradlew test --tests com.reef.platform.application.OrderApplicationServiceTest` in `services/platform-runtime` passed.

## Deferred High-Impact Candidates

1. Split `services/simulator/cmd/load-tester/main.go` (currently large/monolithic) into focused packages (`actions`, `state`, `reporting`, `io`) for maintainability and safer perf changes.
2. Consider a purpose-built book structure (price levels / indexed queues) if we need to push beyond current TPS envelopes with lower GC/lock pressure.
3. Add lightweight micro-benchmarks for matching-engine `SubmitOrder`, `ModifyOrder`, and insertion/match loops to quantify future gains and prevent regressions.
