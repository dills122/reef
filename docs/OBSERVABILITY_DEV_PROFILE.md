# Observability Dev Profile

This runbook describes local observability profile usage during the dev-env sprint.

## Enable profile

```bash
DEV_COMPOSE_PROFILES=observability make dev-up
```

Suggested port overrides when defaults conflict:

```bash
DEV_COMPOSE_PROFILES=observability \
REEF_JAEGER_UI_HOST_PORT=16687 \
REEF_OTEL_GRPC_HOST_PORT=14317 \
REEF_OTEL_HTTP_HOST_PORT=14318 \
make dev-up
```

## Validate profile health

1. Run API smoke:

```bash
make dev-smoke
```

2. Confirm Jaeger UI/API responds:

```bash
curl -fsS http://localhost:16686
curl -fsS http://localhost:16686/api/services
```

## OTEL environment wiring

When observability profile is enabled, services are configured with:
- `OTEL_SERVICE_NAME`
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`
- `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`

## Current limitation

Runtime and engine do not yet emit full distributed traces across the runtime->engine boundary.
Profile validation currently confirms observability infrastructure health and ingestion path readiness.

## Default-on criteria (future)

Before making observability profile default-on:

1. Runtime emits root spans for API and command workflows.
2. Engine emits spans for order handling/matching workflows.
3. Runtime->engine context propagation is validated.
4. Jaeger shows at least one full runtime->engine trace in smoke.
5. Added overhead remains within acceptable local latency tolerance.
